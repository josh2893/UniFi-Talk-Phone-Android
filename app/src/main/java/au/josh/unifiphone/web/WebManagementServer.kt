package au.josh.unifiphone.web

import au.josh.unifiphone.data.SettingsRepository
import au.josh.unifiphone.data.appSettingsFromBackupJson
import au.josh.unifiphone.data.toBackupJson
import fi.iki.elonen.NanoHTTPD
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.MessageDigest
import java.util.Collections
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

class WebManagementServer(
    private val configuredPort: Int,
    private val settingsRepository: SettingsRepository,
) : NanoHTTPD(configuredPort) {

    private val authenticatedSessions = Collections.synchronizedSet(mutableSetOf<String>())

    fun startServer() = start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)

    override fun serve(session: IHTTPSession): Response {
        return runCatching { route(session) }.getOrElse { error ->
            response(Response.Status.INTERNAL_ERROR, NanoHTTPD.MIME_PLAINTEXT, error.message ?: "Server error")
        }
    }

    private fun route(session: IHTTPSession): Response {
        if (session.uri == "/login") return login(session)
        if (session.uri == "/logout") {
            session.cookies.read(SESSION_COOKIE)?.let(authenticatedSessions::remove)
            return redirect("/login")
        }
        if (!isAuthenticated(session)) {
            return if (session.uri.startsWith("/api/")) {
                response(Response.Status.UNAUTHORIZED, "application/json", "{\"error\":\"Unauthorized\"}")
            } else {
                redirect("/login")
            }
        }

        return when {
            session.uri == "/" && session.method == Method.GET ->
                response(Response.Status.OK, NanoHTTPD.MIME_HTML, managementPage())
            session.uri == "/api/settings" && session.method == Method.GET ->
                response(Response.Status.OK, "application/json", currentSettingsJson())
            session.uri == "/api/settings" && session.method == Method.POST ->
                updateSettings(session)
            session.uri == "/api/export" && session.method == Method.GET ->
                response(Response.Status.OK, "application/json", currentSettingsJson()).apply {
                    addHeader("Content-Disposition", "attachment; filename=unifiphone-settings-backup.json")
                }
            session.uri == "/api/import" && session.method == Method.POST ->
                updateSettings(session)
            else -> response(Response.Status.NOT_FOUND, NanoHTTPD.MIME_PLAINTEXT, "Not found")
        }
    }

    private fun login(session: IHTTPSession): Response {
        if (session.method == Method.GET) {
            return response(Response.Status.OK, NanoHTTPD.MIME_HTML, loginPage(error = false))
        }
        if (session.method != Method.POST) {
            return response(Response.Status.BAD_REQUEST, NanoHTTPD.MIME_PLAINTEXT, "Unsupported request")
        }

        parseBody(session)
        val suppliedPin = session.parms["pin"].orEmpty()
        val expectedPin = runBlocking(Dispatchers.IO) {
            settingsRepository.current().doorbellAdminPin.ifBlank { "1234" }
        }
        if (!secureEquals(suppliedPin, expectedPin)) {
            return response(Response.Status.UNAUTHORIZED, NanoHTTPD.MIME_HTML, loginPage(error = true))
        }

        val token = UUID.randomUUID().toString()
        authenticatedSessions += token
        return redirect("/").apply {
            addHeader(
                "Set-Cookie",
                "$SESSION_COOKIE=$token; Path=/; HttpOnly; SameSite=Strict",
            )
        }
    }

    private fun updateSettings(session: IHTTPSession): Response {
        val body = parseBody(session)
        if (body.isBlank()) {
            return response(Response.Status.BAD_REQUEST, "application/json", "{\"error\":\"Empty configuration\"}")
        }
        return runCatching {
            runBlocking(Dispatchers.IO) {
                val current = settingsRepository.current()
                val restored = appSettingsFromBackupJson(body, current)
                settingsRepository.update { restored }
            }
            response(Response.Status.OK, "application/json", "{\"ok\":true}")
        }.getOrElse { error ->
            val message = org.json.JSONObject.quote(error.message ?: "Invalid configuration")
            response(Response.Status.BAD_REQUEST, "application/json", "{\"error\":$message}")
        }
    }

    private fun parseBody(session: IHTTPSession): String {
        val files = HashMap<String, String>()
        session.parseBody(files)
        return files["postData"].orEmpty()
    }

    private fun currentSettingsJson(): String = runBlocking(Dispatchers.IO) {
        settingsRepository.current().toBackupJson()
    }

    private fun isAuthenticated(session: IHTTPSession): Boolean {
        val token = session.cookies.read(SESSION_COOKIE) ?: return false
        return authenticatedSessions.contains(token)
    }

    private fun response(status: Response.Status, mimeType: String, body: String): Response =
        newFixedLengthResponse(status, mimeType, body).apply {
            addHeader("Cache-Control", "no-store")
            addHeader("X-Content-Type-Options", "nosniff")
            addHeader("X-Frame-Options", "DENY")
        }

    private fun redirect(location: String): Response =
        response(Response.Status.REDIRECT_SEE_OTHER, NanoHTTPD.MIME_PLAINTEXT, "Redirecting").apply {
            addHeader("Location", location)
        }

    companion object {
        private const val SESSION_COOKIE = "unifiphone_admin"

        fun localIpv4Address(): String? = runCatching {
            val addresses = Collections.list(NetworkInterface.getNetworkInterfaces())
                .asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { Collections.list(it.inetAddresses).asSequence() }
                .filterIsInstance<Inet4Address>()
                .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }
                .toList()
            (addresses.firstOrNull { it.isSiteLocalAddress } ?: addresses.firstOrNull())
                ?.hostAddress
        }.getOrNull()

        fun localUrl(port: Int): String? = localIpv4Address()?.let { "http://$it:$port" }

        private fun secureEquals(first: String, second: String): Boolean =
            MessageDigest.isEqual(first.toByteArray(), second.toByteArray())
    }
}

private fun WebManagementServer.loginPage(error: Boolean): String = """
<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width,initial-scale=1">
  <title>UniFi Phone Administration</title>
  <style>
    :root{color-scheme:dark;--bg:#090d12;--panel:#111820;--line:#29323d;--text:#f3f6fa;--muted:#99a5b4;--blue:#1478ff;--red:#ff5d64}
    *{box-sizing:border-box}body{margin:0;min-height:100vh;display:grid;place-items:center;background:var(--bg);color:var(--text);font:15px system-ui,sans-serif;padding:24px}
    main{width:min(420px,100%);border:1px solid var(--line);background:var(--panel);padding:30px;border-radius:8px}
    .mark{width:42px;height:42px;display:grid;place-items:center;background:var(--blue);border-radius:8px;font-size:21px;font-weight:750;margin-bottom:22px}
    h1{font-size:24px;margin:0 0 8px}p{color:var(--muted);line-height:1.5;margin:0 0 22px}label{display:block;font-weight:650;margin-bottom:8px}
    input{width:100%;height:48px;border:1px solid var(--line);border-radius:6px;background:#080c11;color:var(--text);padding:0 13px;font-size:18px;outline:none}input:focus{border-color:var(--blue)}
    button{width:100%;height:46px;border:0;border-radius:6px;background:var(--blue);color:white;font-weight:700;margin-top:16px;cursor:pointer}
    .error{color:var(--red);margin:12px 0 0}
  </style>
</head>
<body><main><div class="mark">U</div><h1>Phone administration</h1><p>Enter the doorbell admin PIN to manage this phone.</p>
  <form method="post" action="/login"><label for="pin">Admin PIN</label><input id="pin" name="pin" type="password" inputmode="numeric" autocomplete="current-password" autofocus required>
  <button type="submit">Sign in</button></form>${if (error) "<div class=\"error\">That PIN is not correct.</div>" else ""}</main></body></html>
""".trimIndent()

private fun WebManagementServer.managementPage(): String = """
<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width,initial-scale=1">
  <title>UniFi Phone Administration</title>
  <style>
    :root{color-scheme:dark;--bg:#080c11;--nav:#0d131a;--panel:#111820;--field:#090e14;--line:#29333e;--text:#f3f6fa;--muted:#94a0af;--blue:#1478ff;--green:#35c978;--red:#ff5d64}
    *{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--text);font:14px system-ui,sans-serif;letter-spacing:0}.shell{min-height:100vh;display:grid;grid-template-columns:230px 1fr}
    aside{position:sticky;top:0;height:100vh;background:var(--nav);border-right:1px solid var(--line);padding:22px 14px;display:flex;flex-direction:column}.brand{display:flex;align-items:center;gap:11px;font-weight:760;font-size:17px;padding:0 10px 22px}.logo{width:34px;height:34px;border-radius:7px;background:var(--blue);display:grid;place-items:center}
    nav{display:grid;gap:4px}.tab{height:42px;border:0;border-radius:6px;background:transparent;color:var(--muted);text-align:left;padding:0 12px;font-weight:620;cursor:pointer}.tab.active,.tab:hover{background:#172230;color:var(--text)}.sidefoot{margin-top:auto;border-top:1px solid var(--line);padding-top:15px}.logout{color:var(--muted);text-decoration:none;padding:8px 10px;display:block}
    main{min-width:0}.topbar{height:72px;border-bottom:1px solid var(--line);display:flex;align-items:center;justify-content:space-between;padding:0 32px;position:sticky;top:0;background:rgba(8,12,17,.96);z-index:3}.status{display:flex;gap:8px;align-items:center;color:var(--muted)}.dot{width:8px;height:8px;border-radius:50%;background:var(--green)}
    .actions{display:flex;gap:8px}button,.button{border:1px solid var(--line);height:38px;border-radius:6px;background:var(--panel);color:var(--text);padding:0 14px;font-weight:650;cursor:pointer;text-decoration:none;display:inline-flex;align-items:center}.primary{background:var(--blue);border-color:var(--blue);color:#fff}.content{width:min(940px,100%);padding:30px 32px 60px}
    h1{font-size:27px;margin:0 0 6px}h2{font-size:17px;margin:0 0 18px}.lead{color:var(--muted);margin:0 0 28px}.section{display:none}.section.active{display:block}.group{border-top:1px solid var(--line);padding:24px 0 8px}.grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:16px 18px}.field{min-width:0}.field.wide{grid-column:1/-1}.field label{display:block;font-size:13px;font-weight:650;margin-bottom:7px}.hint{color:var(--muted);font-size:12px;margin-top:5px}
    input,select,textarea{width:100%;border:1px solid var(--line);border-radius:6px;background:var(--field);color:var(--text);padding:10px 11px;font:inherit;outline:none}input,select{height:42px}textarea{min-height:94px;resize:vertical}input:focus,select:focus,textarea:focus{border-color:var(--blue)}
    .toggle{height:58px;border:1px solid var(--line);border-radius:6px;padding:0 12px;display:flex;align-items:center;justify-content:space-between;gap:12px;background:var(--panel)}.toggle span{font-weight:620}.toggle input{width:18px;height:18px;accent-color:var(--blue)}
    .toast{position:fixed;right:24px;bottom:24px;padding:12px 16px;border-radius:6px;background:#173322;border:1px solid #285e3e;color:#baf5d0;display:none}.toast.error{background:#3a171a;border-color:#6d2b31;color:#ffc2c6}
    #importFile{display:none}@media(max-width:760px){.shell{grid-template-columns:1fr}aside{height:auto;position:static;border-right:0;border-bottom:1px solid var(--line);padding:14px}.brand{padding-bottom:12px}nav{grid-template-columns:repeat(3,1fr)}.tab{text-align:center;padding:0 5px}.sidefoot{display:none}.topbar{height:auto;min-height:72px;flex-wrap:wrap;gap:10px;padding:12px 16px}.actions{width:100%;display:grid;grid-template-columns:repeat(3,1fr)}.actions button,.actions .button{justify-content:center;padding:0 8px}.content{padding:24px 16px 50px}.grid{grid-template-columns:1fr}}
  </style>
</head>
<body><div class="shell"><aside><div class="brand"><div class="logo">U</div><span>UniFi Phone</span></div><nav id="tabs"></nav><div class="sidefoot"><a class="logout" href="/logout">Sign out</a></div></aside>
<main><header class="topbar"><div class="status"><span class="dot"></span><span>Connected to phone</span></div><div class="actions"><button class="secondary" id="importButton">Import</button><a class="button secondary" href="/api/export">Export</a><button class="primary" id="saveButton">Save changes</button></div></header>
<div class="content"><section><h1 id="pageTitle">Settings</h1><p class="lead" id="pageLead"></p></section><div id="sections"></div></div></main></div><input id="importFile" type="file" accept="application/json"><div class="toast" id="toast"></div>
<script>
const schema=[
 {id:'account',title:'SIP account',lead:'UniFi Talk registration and phone identity.',groups:[
  {title:'Connection',fields:[['sipServer','Server or hostname'],['sipPort','SIP port'],['sipDomain','SIP domain'],['sipUsername','Username'],['sipPassword','Password','password'],['transport','Transport','select',['UDP','TCP','TLS']]]},
  {title:'Identity',fields:[['phoneLabel','Phone label']]}
 ]},
 {id:'phone',title:'Phone',lead:'Calling, appearance, ringtone, and kiosk behaviour.',groups:[
  {title:'Calling',fields:[['videoCalls','Place video calls by default','toggle'],['showMissedCalls','Show missed calls','toggle'],['videoUpgradeDebug','Show video upgrade debug control','toggle']]},
  {title:'Device',fields:[['themeMode','Theme','select',['SYSTEM','LIGHT','DARK']],['ringtone','Ringtone source'],['kioskEnabled','Kiosk mode','toggle']]}
 ]},
 {id:'video',title:'Video tuning',lead:'Live capture, encoding, and receive display controls.',groups:[
  {title:'Camera and encoder',fields:[['videoUseFrontCamera','Use front camera','toggle'],['videoMirror','Extra mirror','toggle'],['videoRotationOffset','Rotation offset','number'],['videoResolution','Resolution short edge','number'],['videoBitrateKbps','Bitrate (kbps)','number'],['showDebugOverlay','Show debug overlay','toggle']]},
  {title:'Framing',fields:[['videoScaleMode','Scale mode','select',['fit','fill']],['videoTargetAspect','Target aspect','select',['source','9:16','3:4','1:1']],['videoStretchFixPercent','Outgoing width correction (%)','number'],['videoReceiveStretchFixPercent','Receive width correction (%)','number']]}
 ]},
 {id:'doorbell',title:'Doorbell',lead:'Visitor display, calling target, chime, and access controls.',groups:[
  {title:'Mode',fields:[['doorbellEnabled','Doorbell mode','toggle'],['doorbellBanner','Banner'],['doorbellTitle','Door or building name'],['doorbellAddress','Address'],['doorbellTarget','Video group extensions'],['doorbellAdminPin','Admin PIN','password']]},
  {title:'Messages',fields:[['doorbellMessageEnabled','Show custom message','toggle'],['doorbellInstruction','Custom message','textarea'],['doorbellIdleMessage','Idle message'],['doorbellNoAnswerMessage','No answer message','textarea']]},
  {title:'Chime',fields:[['doorbellChimeUntilCallEnds','Play until ringing ends','toggle'],['doorbellChimeCount','Chime count','number']]}
 ]},
 {id:'delivery',title:'Delivery',lead:'Delivery instructions, recipients, webhooks, and authentication.',groups:[
  {title:'Experience',fields:[['doorbellDeliveryEnabled','Show delivery option','toggle'],['doorbellDeliveryInstructions','Delivery instructions','textarea'],['doorbellDeliveryThankYou','Thank-you message','textarea']]},
  {title:'Webhook authentication',fields:[['doorbellDeliveryApiKeyHeader','API key header (Protect: X-API-KEY)'],['doorbellDeliveryApiKey','API key (no Bearer for Protect)','password']]},
  {title:'Recipients',fields:[['doorbellDeliveryPerson1Name','Person 1 name'],['doorbellDeliveryPerson1Webhook','Person 1 webhook'],['doorbellDeliveryPerson2Name','Person 2 name'],['doorbellDeliveryPerson2Webhook','Person 2 webhook'],['doorbellDeliveryPerson3Name','Person 3 name'],['doorbellDeliveryPerson3Webhook','Person 3 webhook'],['doorbellDeliveryOtherName','Other name'],['doorbellDeliveryOtherWebhook','Other webhook']]}
 ]},
 {id:'system',title:'System',lead:'Local administration and configuration transfer.',groups:[
  {title:'Web management',fields:[['webManagementEnabled','Enable web management','toggle'],['webManagementPort','Web server port','number']]}
 ]}
];
let settings={};
const tabs=document.getElementById('tabs'),sections=document.getElementById('sections');
function fieldHtml(f){const key=f[0],label=f[1],type=f[2]||'text',options=f[3]||[];if(type==='toggle')return '<label class="toggle field"><span>'+label+'</span><input type="checkbox" data-key="'+key+'"></label>';if(type==='textarea')return '<div class="field wide"><label>'+label+'</label><textarea data-key="'+key+'"></textarea></div>';if(type==='select')return '<div class="field"><label>'+label+'</label><select data-key="'+key+'">'+options.map(o=>'<option value="'+o+'">'+o+'</option>').join('')+'</select></div>';return '<div class="field"><label>'+label+'</label><input type="'+type+'" data-key="'+key+'"></div>'}
schema.forEach((s,index)=>{tabs.insertAdjacentHTML('beforeend','<button class="tab'+(index===0?' active':'')+'" data-tab="'+s.id+'">'+s.title+'</button>');const groups=s.groups.map(g=>'<div class="group"><h2>'+g.title+'</h2><div class="grid">'+g.fields.map(fieldHtml).join('')+'</div></div>').join('');sections.insertAdjacentHTML('beforeend','<div class="section'+(index===0?' active':'')+'" id="'+s.id+'">'+groups+'</div>')});
function setTab(id){document.querySelectorAll('.tab').forEach(x=>x.classList.toggle('active',x.dataset.tab===id));document.querySelectorAll('.section').forEach(x=>x.classList.toggle('active',x.id===id));const item=schema.find(x=>x.id===id);document.getElementById('pageTitle').textContent=item.title;document.getElementById('pageLead').textContent=item.lead}
tabs.addEventListener('click',e=>{if(e.target.dataset.tab)setTab(e.target.dataset.tab)});setTab('account');
function populate(){document.querySelectorAll('[data-key]').forEach(el=>{const value=settings[el.dataset.key];if(el.type==='checkbox')el.checked=Boolean(value);else if(value!==undefined)el.value=value})}
async function load(){const r=await fetch('/api/settings');if(!r.ok){location='/login';return}settings=await r.json();populate()}
function collect(){document.querySelectorAll('[data-key]').forEach(el=>{settings[el.dataset.key]=el.type==='checkbox'?el.checked:el.type==='number'?parseInt(el.value||'0',10):el.value})}
function toast(message,error){const t=document.getElementById('toast');t.textContent=message;t.classList.toggle('error',Boolean(error));t.style.display='block';setTimeout(()=>t.style.display='none',3200)}
document.getElementById('saveButton').onclick=async()=>{collect();const r=await fetch('/api/settings',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(settings)});if(r.ok)toast('Settings saved');else toast((await r.json()).error||'Save failed',true)};
document.getElementById('importButton').onclick=()=>document.getElementById('importFile').click();document.getElementById('importFile').onchange=async e=>{const f=e.target.files[0];if(!f)return;const r=await fetch('/api/import',{method:'POST',headers:{'Content-Type':'application/json'},body:await f.text()});if(r.ok){toast('Configuration imported');await load()}else toast((await r.json()).error||'Import failed',true)};
load().catch(()=>toast('Could not connect to the phone',true));
</script></body></html>
""".trimIndent()
