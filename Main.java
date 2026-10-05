import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.*;

/**
 * Couple Chat - single file Java server (no Maven, no libraries).
 * Run: VS Code madhe "Run" button, kiva terminal madhe:  java Main.java
 * Browser: http://localhost:8080
 *
 * Server 3 kaam karto:
 *  1) Chat page (HTML) dakhavto
 *  2) Code dwara 2 lokanna ek room madhe jodto
 *  3) Chat ani call-setup messages (WebRTC) dusrya partner kade pathavto
 */
public class Main {

    static final int PORT = 8080;
    static final String WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    static final Map<String, List<Client>> rooms = new HashMap<>();

    // ---------------------------------------------------------------- main
    public static void main(String[] args) throws Exception {
        ServerSocket server = new ServerSocket(PORT);
        System.out.println("Laptop var:  http://localhost:" + PORT);
        for (String ip : lanAddresses()) {
            System.out.println("Same Wi-Fi:  http://" + ip + ":" + PORT + "  (chat chalel; call sathi HTTPS lagto)");
        }
        while (true) {
            Socket s = server.accept();
            Thread t = new Thread(() -> handle(s));
            t.setDaemon(true);
            t.start();
        }
    }

    static List<String> lanAddresses() {
        List<String> list = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) list.add(a.getHostAddress());
                }
            }
        } catch (Exception ignored) { }
        return list;
    }

    // ---------------------------------------------------------------- HTTP
    static void handle(Socket s) {
        try {
            InputStream in = new BufferedInputStream(s.getInputStream());
            String requestLine = readLine(in);
            if (requestLine == null) return;
            Map<String, String> headers = new HashMap<>();
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int i = line.indexOf(':');
                if (i > 0) headers.put(line.substring(0, i).trim().toLowerCase(), line.substring(i + 1).trim());
            }
            String upgrade = headers.get("upgrade");
            if (upgrade != null && upgrade.equalsIgnoreCase("websocket")) {
                websocket(s, in, headers);
            } else {
                String[] parts = requestLine.split(" ");
                String path = parts.length > 1 ? parts[1] : "/";
                serve(s, path);
            }
        } catch (Exception ignored) {
        } finally {
            try { s.close(); } catch (IOException ignored) { }
        }
    }

    static void serve(Socket s, String path) throws IOException {
        boolean ok = path.equals("/") || path.startsWith("/index.html") || path.startsWith("/?");
        byte[] body = (ok ? HTML : "Not found").getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + (ok ? "200 OK" : "404 Not Found") + "\r\n"
                + "Content-Type: text/html; charset=utf-8\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n\r\n";
        OutputStream out = s.getOutputStream();
        out.write(head.getBytes(StandardCharsets.UTF_8));
        out.write(body);
        out.flush();
    }

    static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') b.write(c);
        }
        if (c == -1 && b.size() == 0) return null;
        return b.toString(StandardCharsets.UTF_8);
    }

    // ----------------------------------------------------------- WebSocket
    static class Client {
        final OutputStream out;
        String room;

        Client(Socket s) throws IOException { out = s.getOutputStream(); }

        synchronized void sendFrame(int opcode, byte[] data) {
            try {
                out.write(0x80 | opcode);
                int len = data.length;
                if (len < 126) {
                    out.write(len);
                } else if (len < 65536) {
                    out.write(126);
                    out.write(len >> 8);
                    out.write(len & 0xFF);
                } else {
                    out.write(127);
                    long l = len;
                    for (int i = 7; i >= 0; i--) out.write((int) ((l >> (8 * i)) & 0xFF));
                }
                out.write(data);
                out.flush();
            } catch (IOException ignored) { }
        }

        void send(String text) { sendFrame(1, text.getBytes(StandardCharsets.UTF_8)); }
    }

    static void websocket(Socket s, InputStream in, Map<String, String> headers) throws Exception {
        String key = headers.get("sec-websocket-key");
        if (key == null) return;
        String accept = Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-1").digest((key + WS_GUID).getBytes(StandardCharsets.UTF_8)));
        String resp = "HTTP/1.1 101 Switching Protocols\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n";
        OutputStream out = s.getOutputStream();
        out.write(resp.getBytes(StandardCharsets.UTF_8));
        out.flush();

        Client me = new Client(s);
        ByteArrayOutputStream fragments = new ByteArrayOutputStream();
        try {
            while (true) {
                int b0 = in.read();
                int b1 = in.read();
                if (b0 == -1 || b1 == -1) break;
                boolean fin = (b0 & 0x80) != 0;
                int opcode = b0 & 0x0F;
                boolean masked = (b1 & 0x80) != 0;
                long len = b1 & 0x7F;
                if (len == 126) {
                    len = (in.read() << 8) | in.read();
                } else if (len == 127) {
                    len = 0;
                    for (int i = 0; i < 8; i++) len = (len << 8) | in.read();
                }
                byte[] mask = new byte[4];
                if (masked) readFully(in, mask);
                byte[] payload = new byte[(int) len];
                readFully(in, payload);
                if (masked) for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i % 4];

                if (opcode == 8) break;                              // close
                if (opcode == 9) { me.sendFrame(10, payload); continue; } // ping -> pong
                if (opcode == 10) continue;                          // pong
                if (opcode == 1 || opcode == 0) {                    // text / continuation
                    fragments.write(payload);
                    if (fin) {
                        String text = fragments.toString(StandardCharsets.UTF_8);
                        fragments.reset();
                        onMessage(me, text);
                    }
                }
            }
        } finally {
            leave(me);
        }
    }

    static void readFully(InputStream in, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n == -1) throw new EOFException();
            off += n;
        }
    }

    // --------------------------------------------------------------- rooms
    static void onMessage(Client me, String text) {
        String type = field(text, "type");
        if ("join".equals(type)) {
            join(me, field(text, "code"));
            return;
        }
        synchronized (rooms) {
            if (me.room == null) return;
            List<Client> room = rooms.get(me.room);
            if (room == null) return;
            for (Client c : room) {
                if (c != me) c.send(text);   // dusrya partner la jasa ahe tasa pathav
            }
        }
    }

    static void join(Client me, String code) {
        if (code == null || code.trim().length() < 4) {
            me.send("{\"type\":\"error\",\"message\":\"Code kamit kami 4 characters cha pahije\"}");
            return;
        }
        final String key = code.trim();
        synchronized (rooms) {
            if (me.room != null) return;
            List<Client> room = rooms.computeIfAbsent(key, k -> new ArrayList<>());
            if (room.size() >= 2) {
                me.send("{\"type\":\"full\"}");
                return;
            }
            room.add(me);
            me.room = key;
            me.send("{\"type\":\"joined\",\"count\":" + room.size() + "}");
            if (room.size() == 2) {
                for (Client c : room) if (c != me) c.send("{\"type\":\"peer-joined\"}");
            }
        }
    }

    static void leave(Client me) {
        synchronized (rooms) {
            if (me.room == null) return;
            List<Client> room = rooms.get(me.room);
            if (room != null) {
                room.remove(me);
                for (Client c : room) c.send("{\"type\":\"peer-left\"}");
                if (room.isEmpty()) rooms.remove(me.room);
            }
        }
    }

    static String field(String json, String name) {
        Matcher m = Pattern.compile("\"" + name + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }

    // ---------------------------------------------------------- chat page
    static final String HTML = """
<!DOCTYPE html>
<html lang="mr">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Couple Chat</title>
<style>
  * { box-sizing: border-box; }
  body { margin:0; font-family: system-ui, sans-serif; background:#1a1020; color:#f3e9f5; height:100vh; }
  .screen { display:none; height:100vh; }
  .screen.active { display:flex; flex-direction:column; }
  #join { align-items:center; justify-content:center; gap:14px; padding:20px; text-align:center; }
  #join h1 { margin:0; }
  input[type=text], input[type=password] { padding:12px; border-radius:10px; border:none; font-size:16px; width:260px; }
  button { padding:10px 16px; border:none; border-radius:10px; font-size:15px; cursor:pointer; background:#e0457b; color:#fff; }
  button:disabled { opacity:.4; cursor:not-allowed; }
  button.gray { background:#4a3a55; }
  button.red { background:#d83a3a; }
  header { padding:10px 14px; background:#2a1a33; display:flex; justify-content:space-between; align-items:center; gap:8px; flex-wrap:wrap; }
  #status { font-size:13px; opacity:.85; }
  #messages { flex:1; overflow-y:auto; padding:14px; display:flex; flex-direction:column; gap:8px; }
  .msg { max-width:75%; padding:8px 12px; border-radius:14px; word-wrap:break-word; }
  .me { align-self:flex-end; background:#e0457b; }
  .them { align-self:flex-start; background:#3a2a45; }
  #composer { display:flex; gap:8px; padding:10px; background:#2a1a33; }
  #composer input { flex:1; width:auto; }
  #incoming { display:none; padding:12px; background:#3b2a1a; text-align:center; }
  #incoming.show { display:block; }
  #callBox { display:none; position:fixed; inset:0; background:#000; z-index:10; }
  #callBox.show { display:block; }
  #remoteVideo { width:100%; height:100%; object-fit:cover; background:#111; }
  #localVideo { position:absolute; right:12px; top:12px; width:110px; border-radius:10px; background:#222; }
  #callControls { position:absolute; bottom:24px; left:0; right:0; display:flex; justify-content:center; gap:12px; }
</style>
</head>
<body>

<div id="join" class="screen active">
  <h1>💕 Couple Chat</h1>
  <p>both of you enter the same secret code </p>
  <input type="password" id="code" placeholder="Secret code" autocomplete="off">
  <button onclick="joinRoom()">Join</button>
  <div id="joinError" style="color:#ff8a8a"></div>
</div>

<div id="chat" class="screen">
  <header>
    <span id="status">Partner chi vaat pahtoy...</span>
    <span>
      <button id="btnVoice" class="gray" onclick="startCall('audio')" disabled>📞 Voice</button>
      <button id="btnVideo" class="gray" onclick="startCall('video')" disabled>🎥 Video</button>
    </span>
  </header>
  <div id="incoming">
    <span id="incomingText"></span>
    <button onclick="acceptCall()">Accept</button>
    <button class="red" onclick="declineCall()">Decline</button>
  </div>
  <div id="messages"></div>
  <div id="composer">
    <input type="text" id="msgInput" placeholder="Message liha..." autocomplete="off">
    <button onclick="sendChat()">Send</button>
  </div>
</div>

<div id="callBox">
  <video id="remoteVideo" autoplay playsinline></video>
  <video id="localVideo" autoplay playsinline muted></video>
  <div id="callControls">
    <button class="gray" id="btnMute" onclick="toggleMute()">🎤 Mute</button>
    <button class="gray" id="btnCam" onclick="toggleCam()">📷 Camera off</button>
    <button class="red" onclick="hangup()">❌ End</button>
  </div>
</div>

<script>
const $ = id => document.getElementById(id);
const rtcConfig = { iceServers: [{ urls: 'stun:stun.l.google.com:19302' }] };
let ws, pc, localStream, pendingOffer = null, pendingCandidates = [], partnerOnline = false;

function joinRoom() {
  const code = $('code').value.trim();
  if (code.length < 4) { $('joinError').textContent = ' The password must be at least four characters long'; return; }
  const proto = location.protocol === 'https:' ? 'wss://' : 'ws://';
  ws = new WebSocket(proto + location.host + '/ws');
  ws.onopen = () => ws.send(JSON.stringify({ type: 'join', code }));
  ws.onmessage = e => handle(JSON.parse(e.data));
  ws.onclose = () => { $('status').textContent = 'Connection tutla. Page refresh kara.'; setPartner(false); };
}

function send(obj) { if (ws && ws.readyState === 1) ws.send(JSON.stringify(obj)); }

function setPartner(online) {
  partnerOnline = online;
  $('status').textContent = online ? '🟢 Partner online' : '⚪ witing for  Partner ...';
  $('btnVoice').disabled = !online;
  $('btnVideo').disabled = !online;
}

async function handle(m) {
  switch (m.type) {
    case 'joined':
      $('join').classList.remove('active'); $('chat').classList.add('active');
      setPartner(m.count === 2);
      break;
    case 'full': $('joinError').textContent = ' This room is already full (2 people available).'; break;
    case 'error': $('joinError').textContent = m.message; break;
    case 'peer-joined': setPartner(true); break;
    case 'peer-left': setPartner(false); endCall(); break;
    case 'chat': addMsg(m.text, false); break;
    case 'offer':
      pendingOffer = m;
      $('incomingText').textContent = m.kind === 'video' ? '🎥 Video call is coming  ' : '📞 Voice call is coming  ';
      $('incoming').classList.add('show');
      break;
    case 'answer':
      if (pc) { await pc.setRemoteDescription(m.sdp); flushCandidates(); }
      break;
    case 'candidate':
      if (pc && pc.remoteDescription) { try { await pc.addIceCandidate(m.candidate); } catch (e) {} }
      else pendingCandidates.push(m.candidate);
      break;
    case 'decline': addSystem('Partneer declined the call'); endCall(); break;
    case 'hangup': endCall(); break;
  }
}

/* ---------- Chat ---------- */
function addMsg(text, mine) {
  const d = document.createElement('div');
  d.className = 'msg ' + (mine ? 'me' : 'them');
  d.textContent = text;
  $('messages').appendChild(d);
  $('messages').scrollTop = $('messages').scrollHeight;
}
function addSystem(text) {
  const d = document.createElement('div');
  d.style.cssText = 'text-align:center;font-size:12px;opacity:.6';
  d.textContent = text;
  $('messages').appendChild(d);
}
function sendChat() {
  const t = $('msgInput').value.trim();
  if (!t) return;
  send({ type: 'chat', text: t });
  addMsg(t, true);
  $('msgInput').value = '';
}
$('msgInput').addEventListener('keydown', e => { if (e.key === 'Enter') sendChat(); });
$('code').addEventListener('keydown', e => { if (e.key === 'Enter') joinRoom(); });

/* ---------- Calling (WebRTC) ---------- */
function createPC() {
  pc = new RTCPeerConnection(rtcConfig);
  pc.onicecandidate = e => { if (e.candidate) send({ type: 'candidate', candidate: e.candidate }); };
  pc.ontrack = e => { $('remoteVideo').srcObject = e.streams[0]; };
  pc.onconnectionstatechange = () => {
    if (pc && (pc.connectionState === 'failed' || pc.connectionState === 'closed')) endCall();
  };
}

async function getMedia(kind) {
  localStream = await navigator.mediaDevices.getUserMedia({ audio: true, video: kind === 'video' });
  $('localVideo').srcObject = localStream;
  $('localVideo').style.display = kind === 'video' ? 'block' : 'none';
  $('btnCam').style.display = kind === 'video' ? 'inline-block' : 'none';
  localStream.getTracks().forEach(t => pc.addTrack(t, localStream));
}

async function startCall(kind) {
  try {
    createPC();
    await getMedia(kind);
    const offer = await pc.createOffer();
    await pc.setLocalDescription(offer);
    send({ type: 'offer', kind, sdp: pc.localDescription });
    $('callBox').classList.add('show');
  } catch (err) {
    alert('Mic/Camera permission milali nahi, kiva HTTPS nahi: ' + err.message);
    endCall();
  }
}

async function acceptCall() {
  const m = pendingOffer; pendingOffer = null;
  $('incoming').classList.remove('show');
  try {
    createPC();
    await getMedia(m.kind);
    await pc.setRemoteDescription(m.sdp);
    flushCandidates();
    const answer = await pc.createAnswer();
    await pc.setLocalDescription(answer);
    send({ type: 'answer', sdp: pc.localDescription });
    $('callBox').classList.add('show');
  } catch (err) {
    alert('Mic/Camera permission not received OR No https: ' + err.message);
    send({ type: 'decline' });
    endCall();
  }
}

function declineCall() {
  pendingOffer = null; pendingCandidates = [];
  $('incoming').classList.remove('show');
  send({ type: 'decline' });
}

async function flushCandidates() {
  for (const c of pendingCandidates) { try { await pc.addIceCandidate(c); } catch (e) {} }
  pendingCandidates = [];
}

function hangup() { send({ type: 'hangup' }); endCall(); }

function endCall() {
  if (pc) { pc.close(); pc = null; }
  if (localStream) { localStream.getTracks().forEach(t => t.stop()); localStream = null; }
  $('remoteVideo').srcObject = null;
  $('localVideo').srcObject = null;
  $('callBox').classList.remove('show');
  $('incoming').classList.remove('show');
  pendingOffer = null; pendingCandidates = [];
}

function toggleMute() {
  if (!localStream) return;
  const t = localStream.getAudioTracks()[0];
  t.enabled = !t.enabled;
  $('btnMute').textContent = t.enabled ? '🎤 Mute' : '🔇 Unmute';
}
function toggleCam() {
  if (!localStream) return;
  const t = localStream.getVideoTracks()[0];
  if (!t) return;
  t.enabled = !t.enabled;
  $('btnCam').textContent = t.enabled ? '📷 Camera off' : '📷 Camera on';
}
</script>
</body>
</html>
""";
}
