package com.example.yuanbaossehook;

import android.app.Application;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * YuanBao Local OpenAI Bridge v3.3
 * 相对 3.2：
 *   - 新增 stripStaleContext()：剥离 system prompt 里 ## Retrieved Memory / ## Current Plan 段落
 *   - extractPrompt() 对 system 消息先清洗再拼
 *   目的：GitHubK Studio 每次都会注入旧 Memory/Plan，导致元宝总在回答旧话题
 */
public final class MainHook implements IXposedHookLoadPackage {
    private static final String TAG = "YB-Bridge";
    private static final String TARGET = "com.tencent.hunyuan.app.chat";
    private static final int PORT = 8318;
    private static final long STREAM_TIMEOUT_MS = 60_000L;
    private static final int MAX_PROMPT_CHARS = 16_000;
    private static final int SYS_PROMPT_LOG_CHUNK = 2000;

    /** 要剥离的 system prompt 段落锚点（出现即从此处截断到末尾） */
    private static final String[] STALE_ANCHORS = {
        "\n## Retrieved Memory",
        "\n## Current Plan",
        "\n## Stale Memory",
        "\n## Stale Plan",
        "\n## Memory",
        "\n## Plan"
    };

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final AtomicInteger SEQ = new AtomicInteger(1);
    private static final Object CTX_LOCK = new Object();
    private static WeakReference<Object> activeI6 = new WeakReference<>(null);

    private static final ConcurrentHashMap<String, RequestContext> REQUESTS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Integer, String> MSG_TO_REQ = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, String> TOKEN_TO_REQ = new ConcurrentHashMap<>();
    private static final ThreadLocal<String> CURRENT_REQ = new ThreadLocal<>();
    private static final Deque<String> PENDING = new ArrayDeque<>();
    private static File logFile;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) throws Throwable {
        if (!TARGET.equals(lp.packageName)) return;
        initLog(lp);
        log("===== YuanBao Local OpenAI Bridge 3.3 =====");
        log("process=" + lp.processName);
        installApplicationStart();
        installContextHook(lp);
        installMessageCtorHook(lp);
        installMessageHook(lp);
        installSendOrchestratorHooks(lp);
        installRequestBuilderHook(lp);
        installSseHooks(lp);
    }

    private void installApplicationStart() {
        try {
            XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    LocalHttpServer.start();
                }
            });
            log("[OK] Application.onCreate");
        } catch (Throwable t) { log("[MISS] Application.onCreate " + t); }
    }

    private void installContextHook(XC_LoadPackage.LoadPackageParam lp) {
        try {
            Class<?> a1 = XposedHelpers.findClass("hb.a1", lp.classLoader);
            for (Method m : a1.getDeclaredMethods()) {
                if (!"onResume".equals(m.getName()) || m.getParameterTypes().length != 0) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        synchronized (CTX_LOCK) { activeI6 = new WeakReference<>(p.thisObject); }
                        log("[CTX] active=" + p.thisObject.getClass().getName());
                    }
                });
                log("[OK] hb.a1.onResume");
                return;
            }
        } catch (Throwable t) { log("[MISS] hb.a1.onResume " + t); }
    }

    private void installMessageCtorHook(XC_LoadPackage.LoadPackageParam lp) {
        try {
            Class<?> mfs = XposedHelpers.findClass("com.tencent.hunyuan.deps.service.bean.chats.MessageForSend", lp.classLoader);
            int n = 0;
            for (Constructor<?> ctor : mfs.getDeclaredConstructors()) {
                XposedBridge.hookMethod(ctor, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        Object msg = p.thisObject;
                        if (msg == null) return;
                        String req = CURRENT_REQ.get();
                        if (req == null) req = peekPending();
                        if (req == null) return;
                        try { XposedHelpers.setAdditionalInstanceField(msg, "yb_req_id", req); } catch (Throwable ignored) {}
                        trySetStringField(msg, "customIntent", req);
                        MSG_TO_REQ.put(System.identityHashCode(msg), req);
                        log("[MSG] ctor bound req=" + req);
                    }
                });
                n++;
            }
            log("[OK] MessageForSend ctor x" + n);
        } catch (Throwable t) { log("[MISS] MessageForSend ctor " + t); }
    }

    private void installMessageHook(XC_LoadPackage.LoadPackageParam lp) {
        try {
            Class<?> pp = XposedHelpers.findClass("com.tencent.hunyuan.app.chat.biz.chats.conversation.base.viewholder.MessageForSendPreprocessor", lp.classLoader);
            for (Method m : pp.getDeclaredMethods()) {
                if (!("a".equals(m.getName()) || "b".equals(m.getName()) || "c".equals(m.getName()))) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        Object msg = p.getResult();
                        if (msg == null || !msg.getClass().getName().contains("MessageForSend")) return;
                        String req = CURRENT_REQ.get();
                        if (req == null) req = peekPending();
                        if (req == null) return;
                        try { XposedHelpers.setAdditionalInstanceField(msg, "yb_req_id", req); } catch (Throwable ignored) {}
                        trySetStringField(msg, "customIntent", req);
                        MSG_TO_REQ.put(System.identityHashCode(msg), req);
                        log("[MSG] a/b/c bound req=" + req + " method=" + m.getName());
                    }
                });
            }
            log("[OK] MessageForSendPreprocessor a/b/c");
        } catch (Throwable t) { log("[MISS] MessageForSendPreprocessor " + t); }
    }

    private void installSendOrchestratorHooks(XC_LoadPackage.LoadPackageParam lp) {
        try {
            Class<?> a4 = XposedHelpers.findClass("I7.a4", lp.classLoader);
            for (Method m : a4.getDeclaredMethods()) {
                final String name = m.getName();
                if (!"A".equals(name) && !"m".equals(name)) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        String req = findReqInArgs(p.args);
                        if (req == null) req = peekPending();
                        if (req == null) { log("[SEND] I7.a4." + name + " matched=<none>"); return; }
                        CURRENT_REQ.set(req);
                        if (p.args != null) {
                            for (Object a : p.args) {
                                if (a != null && a.getClass().getName().contains("MessageForSend")) {
                                    MSG_TO_REQ.put(System.identityHashCode(a), req);
                                    try { XposedHelpers.setAdditionalInstanceField(a, "yb_req_id", req); } catch (Throwable ignored) {}
                                }
                            }
                        }
                        log("[SEND] I7.a4." + name + " matched=" + req + " args=" + (p.args == null ? 0 : p.args.length));
                    }
                    @Override protected void afterHookedMethod(MethodHookParam p) { }
                });
                log("[OK] I7.a4." + name);
            }
        } catch (Throwable t) { log("[MISS] I7.a4 A/m " + t); }

        try {
            Class<?> i6 = XposedHelpers.findClass("hb.I6", lp.classLoader);
            for (Method m : i6.getDeclaredMethods()) {
                if (!"s3".equals(m.getName()) || m.getParameterTypes().length != 3) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        if (p.args == null || p.args.length == 0 || !(p.args[0] instanceof String)) return;
                        String text = (String) p.args[0];
                        if (text.isEmpty()) return;
                        String req = pollPending();
                        if (req != null) CURRENT_REQ.set(req);
                        log("[SEND] hb.I6.s3 textLen=" + text.length() + " req=" + req);
                    }
                    @Override protected void afterHookedMethod(MethodHookParam p) { }
                });
                log("[OK] hb.I6.s3");
            }
        } catch (Throwable t) { log("[MISS] hb.I6.s3 " + t); }
    }

    private static String findReqInArgs(Object[] args) {
        if (args == null) return null;
        for (Object a : args) {
            if (a == null) continue;
            try {
                Object x = XposedHelpers.getAdditionalInstanceField(a, "yb_req_id");
                if (x instanceof String) return (String) x;
            } catch (Throwable ignored) {}
            String mapped = MSG_TO_REQ.get(System.identityHashCode(a));
            if (mapped != null) return mapped;
        }
        return null;
    }

    private void installRequestBuilderHook(XC_LoadPackage.LoadPackageParam lp) {
        try {
            Class<?> n1 = XposedHelpers.findClass("androidx.appcompat.widget.n1", lp.classLoader);
            for (Method m : n1.getDeclaredMethods()) {
                if (!"e".equals(m.getName()) || m.getParameterTypes().length != 0) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        Object qrD = p.getResult();
                        String req = CURRENT_REQ.get();
                        if (req == null) req = peekPending();
                        if (qrD == null) return;
                        String token = safeStringField(qrD, "g");
                        if (req == null) { log("[REQ] qr.d req=<none> tokenLen=" + (token == null ? -1 : token.length())); return; }
                        if (token != null && !token.isEmpty()) {
                            TOKEN_TO_REQ.put(token, req);
                            log("[REQ] qr.d req=" + req + " tokenLen=" + token.length());
                        } else log("[REQ] qr.d req=" + req + " token=<none>");
                    }
                });
                log("[OK] n1.e");
            }
        } catch (Throwable t) { log("[MISS] n1.e " + t); }
    }

    private void installSseHooks(XC_LoadPackage.LoadPackageParam lp) {
        try {
            Class<?> piE = XposedHelpers.findClass("pi.e", lp.classLoader);
            for (Method m : piE.getDeclaredMethods()) {
                final String name = m.getName();
                if ("b".equals(name) && m.getParameterTypes().length == 1) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            String req = reqFromPi(p.thisObject);
                            log("[EVT] pi.e.b req=" + req);
                            if (req != null) finish(req, false, "");
                        }
                    });
                } else if ("c".equals(name) && m.getParameterTypes().length >= 2) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            String req = reqFromPi(p.thisObject);
                            log("[EVT] pi.e.c req=" + req);
                            if (req != null) finish(req, true, p.args.length > 1 ? String.valueOf(p.args[1]) : "stream failure");
                        }
                    });
                } else if ("onEvent".equals(name)) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            String req = reqFromPi(p.thisObject);
                            if (p.args != null && p.args.length >= 4) {
                                Object idObj = p.args[2];
                                Object dataObj = p.args[3];
                                int idLen = (idObj instanceof String) ? ((String) idObj).length() : -1;
                                int dataLen = (dataObj instanceof String) ? ((String) dataObj).length() : -1;
                                log("[EVT] pi.e.onEvent req=" + req + " idLen=" + idLen + " dataLen=" + dataLen);
                            }
                            if (req == null || p.args == null) return;
                            for (Object a : p.args) {
                                if (a instanceof String && feed(req, (String) a)) { log("[EVT] fed req=" + req); break; }
                            }
                        }
                    });
                }
            }
            log("[OK] pi.e callbacks");
        } catch (Throwable t) { log("[MISS] pi.e callbacks " + t); }
    }

    private static String reqFromPi(Object piE) {
        if (piE == null) return null;
        String token = safeStringField(piE, "f");
        if (token != null) {
            String req = TOKEN_TO_REQ.get(token);
            if (req != null) return req;
        }
        Object piF = getField(piE, "e");
        if (piF != null) {
            String alt = safeStringField(piF, "e");
            if (alt != null) {
                String req = TOKEN_TO_REQ.get(alt);
                if (req != null) return req;
            }
        }
        return null;
    }

    private static boolean feed(String req, String raw) {
        RequestContext c = REQUESTS.get(req);
        if (c == null || raw == null || raw.isEmpty()) return false;
        log("[FEED] raw.len=" + raw.length() + " head=" + raw.substring(0, Math.min(240, raw.length())));
        if ("[DONE]".equals(raw)) { finish(req, false, ""); return true; }
        if (raw.startsWith("{")) {
            try {
                JSONObject o = new JSONObject(raw);
                String type = o.optString("type", "");
                if ("text".equals(type)) {
                    String msg = o.optString("msg", "");
                    if (msg.isEmpty()) return true;
                    if (c.agentStyle) c.agentBuf.append(msg);
                    else c.encoder.emitDelta(msg);
                    return true;
                }
                if ("meta".equals(type)) {
                    String stopReason = o.optString("stopReason", "");
                    boolean endConv = o.optBoolean("endConv", false);
                    if ("stop".equals(stopReason) || endConv) finish(req, false, "");
                    return true;
                }
                if ("modelError".equals(type)) {
                    finish(req, true, o.optString("modelErrorMsg", "model error"));
                    return true;
                }
                return true;
            } catch (Throwable ignored) { return false; }
        }
        return false;
    }

    private static void finish(String req, boolean error, String message) {
        RequestContext c = REQUESTS.get(req);
        if (c == null) return;
        if (c.agentStyle && !error) {
            String text = c.agentBuf.toString();
            StringBuilder sb = new StringBuilder();
            sb.append("{");
            sb.append("\"action\":\"final_answer\",");
            sb.append("\"type\":\"text\",");
            sb.append("\"content\":").append(OpenAiSseEncoder.json(text)).append(",");
            sb.append("\"text\":").append(OpenAiSseEncoder.json(text)).append(",");
            sb.append("\"message\":").append(OpenAiSseEncoder.json(text)).append(",");
            sb.append("\"reply\":").append(OpenAiSseEncoder.json(text)).append(",");
            sb.append("\"answer\":").append(OpenAiSseEncoder.json(text)).append(",");
            sb.append("\"params\":{\"content\":").append(OpenAiSseEncoder.json(text))
              .append(",\"text\":").append(OpenAiSseEncoder.json(text)).append("}");
            sb.append("}");
            c.encoder.emitDelta(sb.toString());
            log("[AGENT] wrapped generic textLen=" + text.length());
        }
        if (error) c.encoder.error(message); else c.encoder.done();
        cleanup(req);
        log("[SSE] " + (error ? "failure" : "close") + " req=" + req);
    }

    private static void cleanup(String req) {
        RequestContext c = REQUESTS.remove(req);
        if (c != null) c.closeSocket();
        for (Map.Entry<String,String> e : TOKEN_TO_REQ.entrySet()) if (req.equals(e.getValue())) TOKEN_TO_REQ.remove(e.getKey(), req);
        for (Map.Entry<Integer,String> e : MSG_TO_REQ.entrySet()) if (req.equals(e.getValue())) MSG_TO_REQ.remove(e.getKey(), req);
    }

    private static void trigger(String req, String prompt) {
        Object ctx;
        synchronized (CTX_LOCK) { ctx = activeI6.get(); }
        if (ctx == null) { finish(req, true, "YuanBao conversation context is not ready"); return; }
        synchronized (PENDING) { PENDING.addLast(req); }
        MAIN.post(() -> {
            try {
                CURRENT_REQ.set(req);
                XposedHelpers.callMethod(ctx, "s3", prompt, "", Boolean.FALSE);
            } catch (Throwable t) {
                synchronized (PENDING) { PENDING.remove(req); }
                finish(req, true, "send trigger failed: " + t.getClass().getSimpleName());
                log("[SEND] trigger failed " + t);
            }
        });
    }

    private static String newReq() { return "REQ-" + SEQ.getAndIncrement(); }
    private static String peekPending() { synchronized (PENDING) { return PENDING.peekFirst(); } }
    private static String pollPending() { synchronized (PENDING) { return PENDING.pollFirst(); } }

    /**
     * === v3.3 新增 ===
     * 剥离 system prompt 里客户端注入的旧 Memory / Plan 段落。
     * 出现任意一个锚点即从该处截断到末尾。
     */
    private static String stripStaleContext(String sp) {
        if (sp == null || sp.isEmpty()) return sp;
        int earliest = -1;
        for (String anchor : STALE_ANCHORS) {
            int idx = sp.indexOf(anchor);
            if (idx >= 0 && (earliest < 0 || idx < earliest)) earliest = idx;
        }
        if (earliest < 0) return sp;
        String cleaned = sp.substring(0, earliest);
        log("[STRIP] system prompt " + sp.length() + " -> " + cleaned.length());
        return cleaned;
    }

    private static final class RequestContext {
        final String id; final String model; final Socket socket; final OpenAiSseEncoder encoder;
        final boolean agentStyle; final String agentActionName;
        final StringBuilder agentBuf = new StringBuilder();
        final CountDownLatch done = new CountDownLatch(1);
        RequestContext(String id, String model, Socket socket, OutputStream out, boolean agentStyle, String agentActionName) {
            this.id=id; this.model=model; this.socket=socket;
            this.encoder=new OpenAiSseEncoder(this, out);
            this.agentStyle=agentStyle; this.agentActionName=agentActionName;
        }
        void closeSocket() { try { socket.close(); } catch (Throwable ignored) {} done.countDown(); }
    }

    private static final class OpenAiSseEncoder {
        final RequestContext owner; final OutputStream out; volatile boolean done;
        final StringBuilder fullText = new StringBuilder();
        OpenAiSseEncoder(RequestContext owner, OutputStream out){this.owner=owner;this.out=out;}
        synchronized void headers(){ write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream; charset=utf-8\r\nCache-Control: no-cache\r\nConnection: keep-alive\r\nAccess-Control-Allow-Origin: *\r\n\r\n"); }
        synchronized void emitRole(){ if(!done) write(chunk("{\"role\":\"assistant\",\"content\":null}", null)); }
        synchronized void emitDelta(String s){ if(done||s==null||s.isEmpty())return; fullText.append(s); write(chunk("{\"content\":"+json(s)+"}", null)); }
        synchronized void emitToolCall(String name, String argsJson){
            if(done)return;
            String tc = "{\"tool_calls\":[{\"index\":0,\"id\":\"call_" + owner.id + "\",\"type\":\"function\",\"function\":{\"name\":" + json(name) + ",\"arguments\":" + json(argsJson) + "}}]}";
            write(chunk(tc, null)); fullText.append(argsJson);
        }
        synchronized void done(){ if(done)return;done=true; write(chunk("{\"content\":null}","stop")); write("data: [DONE]\n\n"); owner.done.countDown(); }
        synchronized void error(String s){ if(done)return;done=true; write(chunk("{\"content\":"+json(s==null?"error":s)+"}","stop")); write("data: [DONE]\n\n"); owner.done.countDown(); }
        private String chunk(String delta,String finish){
            return "data: {\"id\":"+json(owner.id)+",\"object\":\"chat.completion.chunk\",\"created\":"+(System.currentTimeMillis()/1000)+",\"model\":"+json(owner.model)+",\"choices\":[{\"index\":0,\"delta\":"+delta+",\"finish_reason\":"+(finish==null?"null":"\""+finish+"\"")+"}]}\n\n";
        }
        private void write(String s){ try{out.write(s.getBytes(StandardCharsets.UTF_8));out.flush();}catch(IOException e){done=true;owner.done.countDown();} }
        static String json(String s){
            if(s==null)return "null";
            StringBuilder b=new StringBuilder("\"");
            for(int i=0;i<s.length();i++){
                char c=s.charAt(i);
                switch(c){
                    case '"':b.append("\\\"");break;
                    case '\\':b.append("\\\\");break;
                    case '\n':b.append("\\n");break;
                    case '\r':b.append("\\r");break;
                    case '\t':b.append("\\t");break;
                    default:if(c<32)b.append(String.format(Locale.US,"\\u%04x",(int)c));else b.append(c);
                }
            }
            return b.append('"').toString();
        }
    }

    private static final class LocalHttpServer implements Runnable {
        private static volatile boolean started;
        static synchronized void start(){
            if(started)return;
            started=true;
            Thread t=new Thread(new LocalHttpServer(),"YB-HTTP");
            t.setDaemon(true); t.start();
            log("[HTTP] starting 127.0.0.1:"+PORT);
        }
        @Override public void run(){
            try(ServerSocket ss=new ServerSocket(PORT,32,InetAddress.getByName("127.0.0.1"))){
                while(true){ Socket s=ss.accept(); Thread t=new Thread(()->handle(s),"YB-HTTP-client"); t.setDaemon(true); t.start(); }
            }catch(Throwable t){started=false;log("[HTTP] stopped "+t);}
        }

        private static void handle(Socket socket){
            boolean handoff=false;
            try {
                socket.setSoTimeout(15_000);
                BufferedInputStream in=new BufferedInputStream(socket.getInputStream());
                OutputStream out=socket.getOutputStream();
                String requestLine=readLine(in); if(requestLine==null)return;
                String[] first=requestLine.split(" "); if(first.length<2)return;
                String method=first[0], path=first[1];
                Map<String,String> headers=new HashMap<>();
                String line; int len=0;
                while((line=readLine(in))!=null&&!line.isEmpty()){
                    int k=line.indexOf(':');
                    if(k>0){
                        String key=line.substring(0,k).trim().toLowerCase(Locale.ROOT);
                        String val=line.substring(k+1).trim();
                        headers.put(key,val);
                        if("content-length".equals(key))try{len=Integer.parseInt(val);}catch(Exception ignored){}
                    }
                }
                if("OPTIONS".equalsIgnoreCase(method)){
                    writeRaw(out,"HTTP/1.1 204 No Content\r\nAccess-Control-Allow-Origin: *\r\nAccess-Control-Allow-Headers: content-type, authorization\r\nAccess-Control-Allow-Methods: GET, POST, OPTIONS\r\n\r\n");
                    return;
                }
                if("GET".equalsIgnoreCase(method)&&"/health".equals(path)){
                    writeJson(out,200,"{\"status\":\"ok\",\"service\":\"yuanbao-local-bridge\",\"port\":8318}");
                    return;
                }
                if("GET".equalsIgnoreCase(method)&&"/v1/models".equals(path)){
                    writeJson(out,200,"{\"object\":\"list\",\"data\":[{\"id\":\"yuanbao\",\"object\":\"model\",\"owned_by\":\"local-yuanbao-bridge\",\"capabilities\":{\"tools\":false,\"function_calling\":false,\"streaming\":true,\"chat\":true}}]}");
                    return;
                }
                if("POST".equalsIgnoreCase(method)&&"/v1/chat/completions".equals(path)){
                    byte[] body=readBody(in,headers,len);
                    String bodyStr=new String(body,StandardCharsets.UTF_8);
                    log("[REQ-BODY] len=" + bodyStr.length() + " head=" + bodyStr.substring(0, Math.min(800, bodyStr.length())));
                    JSONObject req=new JSONObject(bodyStr);
                    String model=req.optString("model","yuanbao");
                    boolean stream=req.optBoolean("stream",true);

                    String sysPrompt = collectSystemPrompt(req);
                    if (sysPrompt != null && !sysPrompt.isEmpty()) logFullSystemPrompt(sysPrompt);
                    // v3.3：agentStyle 用清洗后的 system prompt 检测
                    String sysPromptClean = stripStaleContext(sysPrompt);
                    JSONArray tools = req.optJSONArray("tools");
                    int toolsLen = tools == null ? 0 : tools.length();
                    boolean agentStyle = detectAgentStyle(sysPromptClean, toolsLen);
                    String agentActionName = agentStyle ? extractRespondActionName(sysPromptClean) : null;

                    String toolChoice = req.optString("tool_choice", "");
                    log("[REQ] model=" + model + " stream=" + stream + " agentStyle=" + agentStyle
                        + " agentAction=" + agentActionName + " tools.len=" + toolsLen
                        + " tool_choice=" + toolChoice + " sysLen=" + (sysPrompt == null ? 0 : sysPrompt.length())
                        + " cleanSysLen=" + (sysPromptClean == null ? 0 : sysPromptClean.length()));

                    String prompt=extractPrompt(req);
                    if(prompt==null||prompt.trim().isEmpty()){
                        String pingId = newReq();
                        String response = "{\"id\":" + OpenAiSseEncoder.json(pingId) + ",\"object\":\"chat.completion\",\"created\":" + (System.currentTimeMillis() / 1000) + ",\"model\":" + OpenAiSseEncoder.json(model) + ",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"pong\"},\"finish_reason\":\"stop\"}]}";
                        writeJson(out, 200, response);
                        return;
                    }

                    String id=newReq();
                    if(stream){
                        RequestContext c=new RequestContext(id,model,socket,out,agentStyle,agentActionName);
                        REQUESTS.put(id,c);
                        c.encoder.headers();
                        c.encoder.emitRole();
                        trigger(id,prompt);
                        scheduleTimeout(id);
                        handoff=true;
                        return;
                    }
                    ByteArrayOutputStream buffer=new ByteArrayOutputStream();
                    RequestContext c=new RequestContext(id,model,socket,buffer,agentStyle,agentActionName);
                    REQUESTS.put(id,c);
                    trigger(id,prompt);
                    boolean completed=c.done.await(STREAM_TIMEOUT_MS,TimeUnit.MILLISECONDS);
                    REQUESTS.remove(id);
                    if(!completed){c.encoder.error("timeout");}
                    String text=c.encoder.fullText.toString();
                    String response="{\"id\":"+OpenAiSseEncoder.json(id)+",\"object\":\"chat.completion\",\"created\":"+(System.currentTimeMillis()/1000)+",\"model\":"+OpenAiSseEncoder.json(model)+",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":"+OpenAiSseEncoder.json(text)+"},\"finish_reason\":\"stop\"}]}";
                    writeJson(out,200,response);
                    return;
                }
                writeJson(out,404,"{\"error\":{\"message\":\"not found\"}}");
            }catch(Throwable t){
                log("[HTTP] request error "+t);
                try{if(!handoff)writeJson(socket.getOutputStream(),500,"{\"error\":{\"message\":"+OpenAiSseEncoder.json(t.toString())+"}}");}catch(Throwable ignored){}
            }finally {
                if(!handoff)try{socket.close();}catch(Throwable ignored){}
            }
        }

        private static void scheduleTimeout(final String id){
            new Thread(()->{
                try{Thread.sleep(STREAM_TIMEOUT_MS);}catch(InterruptedException ignored){return;}
                RequestContext c=REQUESTS.get(id);
                if(c!=null){c.encoder.error("timeout");cleanup(id);}
            },"YB-timeout").start();
        }

        private static String collectSystemPrompt(JSONObject req) {
            try {
                JSONArray a = req.optJSONArray("messages");
                if (a == null) return null;
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < a.length(); i++) {
                    JSONObject m = a.optJSONObject(i);
                    if (m == null) continue;
                    if (!"system".equalsIgnoreCase(m.optString("role", ""))) continue;
                    Object c = m.opt("content");
                    if (c instanceof String) sb.append((String) c).append("\n");
                    else if (c instanceof JSONArray) {
                        JSONArray ar = (JSONArray) c;
                        for (int j = 0; j < ar.length(); j++) {
                            JSONObject x = ar.optJSONObject(j);
                            if (x != null && "text".equals(x.optString("type"))) sb.append(x.optString("text")).append("\n");
                        }
                    }
                }
                return sb.length() == 0 ? null : sb.toString();
            } catch (Throwable ignored) { return null; }
        }

        private static boolean detectAgentStyle(String sp, int toolsLen) {
            if (toolsLen > 0) return false;
            if (sp == null || sp.isEmpty()) return false;
            if (sp.contains("\"action\"") || sp.contains("\"action\" :")) return true;
            if (sp.toLowerCase(Locale.ROOT).contains("respond with json")) return true;
            if (sp.toLowerCase(Locale.ROOT).contains("you must respond with")) return true;
            return false;
        }

        private static String extractRespondActionName(String sp) {
            if (sp == null || sp.isEmpty()) return null;
            LinkedHashSet<String> actions = new LinkedHashSet<>();
            try {
                Matcher m = Pattern.compile("\"action\"\\s*:\\s*\"([a-zA-Z_]+)\"").matcher(sp);
                while (m.find()) actions.add(m.group(1));
            } catch (Throwable ignored) {}
            String[] preferred = {"final_answer", "final", "respond", "reply", "chat", "message", "answer", "text", "output", "say"};
            for (String p : preferred) if (actions.contains(p)) return p;
            for (String a : actions) {
                String lower = a.toLowerCase(Locale.ROOT);
                for (String p : preferred) if (lower.contains(p)) return a;
            }
            return null;
        }

        private static void logFullSystemPrompt(String sp) {
            int total = sp.length();
            int chunks = (total + SYS_PROMPT_LOG_CHUNK - 1) / SYS_PROMPT_LOG_CHUNK;
            log("[SYS-PROMPT] totalLen=" + total + " chunks=" + chunks);
            for (int i = 0; i < total; i += SYS_PROMPT_LOG_CHUNK) {
                int end = Math.min(total, i + SYS_PROMPT_LOG_CHUNK);
                int idx = i / SYS_PROMPT_LOG_CHUNK + 1;
                log("[SYS-PROMPT #" + idx + "/" + chunks + "] " + sp.substring(i, end));
            }
        }

        /**
         * === v3.3 修改 ===
         * 对 role=="system" 的消息先 stripStaleContext 再拼入 prompt，
         * 从源头阻止旧 Memory/Plan 被送到元宝。
         */
        private static String extractPrompt(JSONObject req){
            try{
                JSONArray a=req.optJSONArray("messages");
                if(a==null)return null;
                StringBuilder sb=new StringBuilder();
                for(int i=0;i<a.length();i++){
                    JSONObject m=a.optJSONObject(i);
                    if(m==null)continue;
                    String role=m.optString("role","");
                    Object c=m.opt("content");
                    String text="";
                    if(c instanceof String) text=(String)c;
                    else if(c instanceof JSONArray){
                        StringBuilder b=new StringBuilder();
                        JSONArray ar=(JSONArray)c;
                        for(int j=0;j<ar.length();j++){
                            JSONObject x=ar.optJSONObject(j);
                            if(x!=null&&"text".equals(x.optString("type")))b.append(x.optString("text"));
                        }
                        text=b.toString();
                    }
                    if(text.isEmpty())continue;

                    if("system".equalsIgnoreCase(role)){
                        String cleaned = stripStaleContext(text);
                        sb.append("[SYSTEM INSTRUCTIONS]\n").append(cleaned).append("\n[/SYSTEM INSTRUCTIONS]\n\n");
                    } else if("user".equalsIgnoreCase(role)){
                        sb.append("[USER]\n").append(text).append("\n[/USER]\n\n");
                    } else if("assistant".equalsIgnoreCase(role)){
                        sb.append("[ASSISTANT]\n").append(text).append("\n[/ASSISTANT]\n\n");
                    } else {
                        sb.append("[").append(role).append("]\n").append(text).append("\n\n");
                    }
                }
                String full = sb.toString().trim();
                if (full.isEmpty()) return null;
                if (full.length() > MAX_PROMPT_CHARS) { log("[PROMPT] truncating " + full.length() + " -> " + MAX_PROMPT_CHARS); full = full.substring(0, MAX_PROMPT_CHARS) + "\n\n[truncated]"; }
                log("[PROMPT] totalLen=" + full.length() + " head=" + full.substring(0, Math.min(400, full.length())));
                return full;
            }catch(Throwable ignored){}
            return null;
        }

        private static byte[] readBody(InputStream in,Map<String,String> headers,int len)throws IOException{
            if(len>0)return readFully(in,len);
            String te=headers.get("transfer-encoding");
            if(te!=null&&te.toLowerCase(Locale.ROOT).contains("chunked"))return readChunked(in);
            return new byte[0];
        }
        private static byte[] readChunked(InputStream in)throws IOException{
            ByteArrayOutputStream b=new ByteArrayOutputStream();
            while(true){
                String line=readLine(in);
                if(line==null)throw new EOFException();
                int n=Integer.parseInt(line.trim().split(";",2)[0],16);
                if(n==0){readLine(in);break;}
                b.write(readFully(in,n)); readLine(in);
            }
            return b.toByteArray();
        }
        private static String readLine(InputStream in)throws IOException{
            ByteArrayOutputStream b=new ByteArrayOutputStream();
            int c;
            while((c=in.read())!=-1){
                if(c=='\n')break;
                if(c!='\r')b.write(c);
                if(b.size()>16384)throw new IOException("line too long");
            }
            if(c==-1&&b.size()==0)return null;
            return b.toString(StandardCharsets.UTF_8.name());
        }
        private static byte[] readFully(InputStream in,int n)throws IOException{
            byte[] b=new byte[n]; int p=0;
            while(p<n){ int r=in.read(b,p,n-p); if(r<0)throw new EOFException(); p+=r; }
            return b;
        }
        private static void writeRaw(OutputStream out,String s)throws IOException{ out.write(s.getBytes(StandardCharsets.UTF_8)); out.flush(); }
        private static void writeJson(OutputStream out,int code,String body)throws IOException{
            byte[] b=body.getBytes(StandardCharsets.UTF_8);
            String status=code==200?"OK":code==400?"Bad Request":code==404?"Not Found":"Internal Server Error";
            writeRaw(out,"HTTP/1.1 "+code+" "+status+"\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: "+b.length+"\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n");
            out.write(b); out.flush();
        }
    }

    private static void initLog(XC_LoadPackage.LoadPackageParam lp){
        try{
            Class<?> at=XposedHelpers.findClass("android.app.ActivityThread",lp.classLoader);
            Object cur=XposedHelpers.callStaticMethod(at,"currentActivityThread");
            Object app=XposedHelpers.callMethod(cur,"getApplication");
            File dir=app instanceof Context?((Context)app).getExternalFilesDir(null):null;
            if(dir==null)dir=new File("/storage/emulated/0/Android/data/"+TARGET+"/files");
            if(!dir.exists())dir.mkdirs();
            logFile=new File(dir,"yuanbao_bridge.log");
        }catch(Throwable t){XposedBridge.log(TAG+" initLog "+t);}
    }
    private static Object getField(Object obj,String name){
        if(obj==null)return null;
        Class<?> c=obj.getClass();
        while(c!=null&&c!=Object.class){
            try{ Field f=c.getDeclaredField(name); f.setAccessible(true); return f.get(obj); }catch(Throwable ignored){}
            c=c.getSuperclass();
        }
        return null;
    }
    private static String safeStringField(Object obj,String name){ Object v=getField(obj,name); return v instanceof String?(String)v:null; }
    private static boolean trySetStringField(Object obj,String name,String value){
        if(obj==null)return false;
        Class<?> c=obj.getClass();
        while(c!=null&&c!=Object.class){
            try{ Field f=c.getDeclaredField(name); if(f.getType()==String.class){ f.setAccessible(true); f.set(obj,value); return true; } }catch(Throwable ignored){}
            c=c.getSuperclass();
        }
        return false;
    }
    private static void log(String s){
        String line=new java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS",Locale.US).format(new Date())+" "+s;
        XposedBridge.log(TAG+": "+line);
        if(logFile!=null)try(FileWriter w=new FileWriter(logFile,true)){w.append(line).append('\n');}catch(IOException ignored){}
    }
}