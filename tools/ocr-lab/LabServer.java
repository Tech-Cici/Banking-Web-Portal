import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * A drag-and-drop page for reading collateral documents, served from this machine.
 *
 * <p>IT RUNS THE SAME READING AS THE TERMINAL TOOL. {@link Lab#read} does the work; this only
 * puts a page in front of it. Reading a PDF in the browser instead -- with a JavaScript build
 * of Tesseract -- would have been less code and a different engine, and then every result
 * would describe something the bank does not run.
 *
 * <p>THE JDK'S OWN HTTP SERVER, so this folder still has no dependencies of its own. It binds
 * to the loopback address only: the page accepts an uploaded document and runs a program on
 * it, which is not a thing to expose to a network.
 */
public final class LabServer {

    private LabServer() {}

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 7017;

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/", exchange -> send(exchange, 200, "text/html; charset=utf-8", page()));
        server.createContext("/read", LabServer::read);
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(2));
        server.start();

        System.out.println();
        System.out.println("  OCR lab  →  http://localhost:" + port);
        System.out.println("  reader   →  " + Lab.version(Lab.binary()));
        System.out.println();
        System.out.println("  Drop a PDF on the page. Ctrl-C here when you are done.");
        System.out.println();
    }

    private static void read(HttpExchange exchange) throws java.io.IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            send(exchange, 405, "text/plain", "POST only");
            return;
        }

        String filename = exchange.getRequestHeaders().getFirst("X-Filename");
        if (filename == null || filename.isBlank()) filename = "document.pdf";

        byte[] content = exchange.getRequestBody().readAllBytes();

        try {
            long began = System.currentTimeMillis();
            Lab.Report report = Lab.read(Lab.binary(), filename, content, false);
            long took = System.currentTimeMillis() - began;

            System.out.printf("  read %s (%,d bytes) in %,d ms%n", filename, content.length, took);
            send(exchange, 200, "application/json; charset=utf-8", json(report, content.length, took));
        } catch (Exception failure) {
            System.out.println("  failed on " + filename + ": " + failure);
            send(exchange, 200, "application/json; charset=utf-8",
                    "{\"error\":" + quote(String.valueOf(failure.getMessage())) + "}");
        }
    }

    /* ------------------------------------------------------------------ JSON by hand */

    private static String json(Lab.Report report, int bytes, long millis) {
        StringBuilder out = new StringBuilder("{");
        out.append("\"filename\":").append(quote(report.filename()));
        out.append(",\"kindOfFile\":").append(quote(report.kindOfFile()));
        out.append(",\"pages\":").append(report.pages());
        out.append(",\"bytes\":").append(bytes);
        out.append(",\"millis\":").append(millis);
        out.append(",\"note\":").append(report.note() == null ? "null" : quote(report.note()));
        out.append(",\"text\":").append(quote(report.text()));
        out.append(",\"heading\":")
                .append(report.heading() == null ? "null" : quote(report.heading()));
        out.append(",\"typeCode\":")
                .append(report.typeCode() == null ? "null" : quote(report.typeCode()));
        out.append(",\"typeLabel\":")
                .append(report.typeLabel() == null ? "null" : quote(report.typeLabel()));

        out.append(",\"because\":[");
        for (int i = 0; i < report.markersFound().size(); i++) {
            if (i > 0) out.append(',');
            out.append(quote(report.markersFound().get(i)));
        }
        out.append(']');

        out.append(",\"fields\":").append(mapOf(report.fields()));
        out.append(",\"anything\":").append(mapOf(report.anything()));

        return out.append('}').toString();
    }

    /** A map of label to value, with nulls preserved -- "not read" is itself an answer. */
    private static String mapOf(Map<String, String> values) {
        StringBuilder out = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (!first) out.append(',');
            first = false;
            out.append(quote(entry.getKey())).append(':')
                    .append(entry.getValue() == null ? "null" : quote(entry.getValue()));
        }
        return out.append('}').toString();
    }

    private static String quote(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        return out.append('"').toString();
    }

    private static void send(HttpExchange exchange, int status, String type, String body)
            throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    /* ------------------------------------------------------------------ the page */

    private static String page() {
        return """
            <!doctype html>
            <html lang="en">
            <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>Document reader</title>
            <style>
              :root{
                --ink:#1d2b26; --body:#45574f; --soft:#7b8c84; --line:#e4ebe7;
                --bg:#f5f8f6; --card:#fff;
                --green:#2f7a5c; --green-soft:#e8f3ed;
                --amber:#9a6a12; --amber-soft:#fdf3e0;
                --rose:#9c4a3f;  --rose-soft:#fbeceb;
                --shadow:0 1px 2px rgba(20,40,30,.05), 0 8px 24px rgba(20,40,30,.05);
              }
              *{box-sizing:border-box}
              html{scroll-behavior:smooth}
              body{
                margin:0;background:var(--bg);color:var(--ink);
                font:16px/1.6 -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif;
                -webkit-font-smoothing:antialiased;
              }
              .wrap{max-width:680px;margin:0 auto;padding:52px 20px 90px}

              .title{display:flex;align-items:center;gap:12px;margin-bottom:8px}
              .title svg{width:30px;height:30px;color:var(--green)}
              h1{margin:0;font-size:25px;letter-spacing:-.02em}
              .lede{margin:0 0 30px;color:var(--body);font-size:15.5px;max-width:52ch}

              /* ---------- drop zone ---------- */
              #drop{
                background:var(--card);border:2px dashed #cfdbd5;border-radius:20px;
                padding:52px 28px;text-align:center;cursor:pointer;box-shadow:var(--shadow);
                transition:border-color .18s,background .18s,transform .15s;
              }
              #drop:hover{border-color:var(--green);background:#fbfdfc}
              #drop.over{border-color:var(--green);background:var(--green-soft);transform:scale(1.008)}
              #drop .ico{width:48px;height:48px;color:var(--green);opacity:.9}
              #drop .big{margin:16px 0 6px;font-size:18px;font-weight:600}
              #drop .small{color:var(--soft);font-size:14px;margin:0}
              .choose{margin-top:18px;font:inherit;font-size:14.5px;font-weight:600;color:#fff;
                      background:var(--green);border:0;border-radius:10px;padding:10px 22px;
                      cursor:pointer;transition:filter .15s}
              .choose:hover{filter:brightness(1.08)}
              .choose:active{filter:brightness(.94)}
              .types{margin-top:18px;display:flex;justify-content:center;gap:8px;flex-wrap:wrap}
              .type{font-size:12px;color:var(--body);background:#f1f5f3;border:1px solid var(--line);
                    padding:3px 10px;border-radius:999px}

              /* ---------- cards ---------- */
              .card{background:var(--card);border:1px solid var(--line);border-radius:18px;
                    padding:22px 24px;margin-top:16px;box-shadow:var(--shadow);
                    animation:rise .28s cubic-bezier(.2,.7,.3,1) both}
              @keyframes rise{from{opacity:0;transform:translateY(8px)}to{opacity:1;transform:none}}
              @media (prefers-reduced-motion:reduce){.card{animation:none}}

              .card h2{margin:0 0 4px;font-size:15px;font-weight:650;letter-spacing:-.01em}
              .card .why{margin:0 0 16px;color:var(--soft);font-size:13.5px;line-height:1.5}

              /* ---------- verdict ---------- */
              .verdict{border-radius:18px;padding:22px 24px;margin-top:16px;
                       display:flex;gap:16px;align-items:flex-start;
                       animation:rise .28s cubic-bezier(.2,.7,.3,1) both}
              .verdict.ok{background:var(--green-soft);border:1px solid #cfe5da}
              .verdict.warn{background:var(--amber-soft);border:1px solid #f0e0bf}
              .verdict.bad{background:var(--rose-soft);border:1px solid #f0d3cf}
              .verdict .badge{flex:0 0 42px;height:42px;border-radius:12px;display:grid;
                              place-items:center;background:#fff}
              .verdict svg{width:22px;height:22px}
              .verdict.ok svg{color:var(--green)} .verdict.warn svg{color:var(--amber)}
              .verdict.bad svg{color:var(--rose)}
              .verdict h2{margin:0 0 4px;font-size:19px;letter-spacing:-.01em}
              .verdict.ok h2{color:var(--green)} .verdict.warn h2{color:var(--amber)}
              .verdict.bad h2{color:var(--rose)}
              .verdict p{margin:0;font-size:14px;color:var(--body);line-height:1.55}
              .chips{margin-top:12px;display:flex;flex-wrap:wrap;gap:6px}
              .chip{background:#fff;border:1px solid #d6e6dd;color:var(--green);
                    font-size:12.5px;padding:3px 10px;border-radius:999px}

              /* ---------- quoted heading ---------- */
              .quote{margin:0;padding:14px 18px;border-left:3px solid var(--green);
                     background:#f7faf8;border-radius:0 10px 10px 0;
                     font-size:17px;font-weight:600;line-height:1.4;color:var(--ink)}

              /* ---------- rows ---------- */
              .count{float:right;font-size:12px;font-weight:600;color:var(--green);
                     background:var(--green-soft);padding:2px 9px;border-radius:999px}
              .row{display:flex;justify-content:space-between;align-items:baseline;gap:18px;
                   padding:11px 2px;border-bottom:1px solid var(--line)}
              .row:last-child{border-bottom:0}
              .row .k{color:var(--body);font-size:14.5px;white-space:nowrap}
              .row .v{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:14px;
                      text-align:right;word-break:break-word;font-weight:500}
              .v.none{font-family:inherit;font-style:italic;font-weight:400;color:#a9b5af}

              details{margin-top:2px}
              summary{cursor:pointer;color:var(--green);font-size:14.5px;font-weight:500;
                      list-style:none;user-select:none}
              summary::-webkit-details-marker{display:none}
              summary::before{content:"+ ";font-weight:700}
              details[open] summary::before{content:"\u2212 "}
              pre{margin:14px 0 0;white-space:pre-wrap;word-break:break-word;
                  font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:12.5px;
                  line-height:1.55;background:#f7faf8;border:1px solid var(--line);
                  border-radius:12px;padding:16px;max-height:360px;overflow:auto;color:#53625b}

              .stats{display:flex;gap:22px;flex-wrap:wrap;color:var(--soft);font-size:13px;
                     margin:0}
              .stats b{color:var(--body);font-weight:600}

              .reading{text-align:center;padding:40px 0;color:var(--soft)}
              .dots span{display:inline-block;width:8px;height:8px;margin:0 3px;border-radius:50%;
                         background:var(--green);opacity:.28;animation:blink 1.2s infinite}
              .dots span:nth-child(2){animation-delay:.16s}
              .dots span:nth-child(3){animation-delay:.32s}
              @keyframes blink{0%,80%,100%{opacity:.25}40%{opacity:1}}

              .hidden{display:none}
              footer{margin-top:34px;text-align:center;color:#9aa8a1;font-size:12.5px;line-height:1.7}
              .again{display:inline-block;margin-top:18px;font-size:14px;color:var(--green);
                     cursor:pointer;font-weight:500}
            </style>
            </head>
            <body>
            <div class="wrap">

              <div class="title">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7"
                     stroke-linecap="round" stroke-linejoin="round">
                  <circle cx="11" cy="11" r="7"/><path d="m20 20-3.5-3.5"/>
                </svg>
                <h1>Document reader</h1>
              </div>
              <p class="lede">
                Drop in a land title, a vehicle registration or a company certificate. It reads
                the page the same way the banking portal would, and shows you exactly what it
                could make out &mdash; and what it could not.
              </p>

              <div id="drop">
                <svg class="ico" viewBox="0 0 24 24" fill="none" stroke="currentColor"
                     stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round">
                  <path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z"/>
                  <path d="M14 3v5h5"/><path d="M9 13h6"/><path d="M9 17h4"/>
                </svg>
                <p class="big">Drop a document here</p>
                <p class="small">or choose one from your computer</p>
                <button id="choose" type="button" class="choose">Choose a file</button>
                <div class="types">
                  <span class="type">PDF</span><span class="type">JPEG</span>
                  <span class="type">PNG</span><span class="type">up to 8 MB</span>
                </div>
              </div>

              <!--
                THE INPUT SITS OUTSIDE THE DROP ZONE, DELIBERATELY. Inside it, clicking the
                input bubbles a click back up to the zone, which clicks the input again; the
                browser stops the recursion and the file dialog never opens at all.
              -->
              <input id="file" type="file" accept=".pdf,.jpg,.jpeg,.png" class="hidden">

              <div id="busy" class="card hidden">
                <div class="reading">
                  <div class="dots"><span></span><span></span><span></span></div>
                  <div style="margin-top:12px;font-size:14.5px">Reading the page&hellip;</div>
                  <div style="margin-top:4px;font-size:13px;color:#a3b0a9">
                    Each page is read twice, once per layout assumption.
                  </div>
                </div>
              </div>

              <div id="out"></div>

              <footer>
                Nothing is saved. The file is read in memory and forgotten when the page closes.<br>
                This is a testing tool &mdash; it is not connected to any customer account.
              </footer>
            </div>

            <script>
            const drop=document.getElementById('drop'),input=document.getElementById('file'),
                  busy=document.getElementById('busy'),out=document.getElementById('out');

            const choose=document.getElementById('choose');

            /* The button is the reliable path; the whole zone is a convenience on top of it. */
            choose.addEventListener('click',ev=>{ev.stopPropagation();input.click();});
            drop.addEventListener('click',()=>input.click());

            input.addEventListener('change',()=>{
              if(input.files[0]) send(input.files[0]);
              /* Cleared, so choosing the same file twice still fires a change event. */
              input.value='';
            });
            ['dragenter','dragover'].forEach(e=>drop.addEventListener(e,ev=>{
              ev.preventDefault();drop.classList.add('over');}));
            ['dragleave','drop'].forEach(e=>drop.addEventListener(e,ev=>{
              ev.preventDefault();drop.classList.remove('over');}));
            drop.addEventListener('drop',ev=>{const f=ev.dataTransfer.files[0];if(f)send(f);});

            const esc=s=>String(s).replace(/[&<>]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;'}[c]));
            const nice=k=>k.replace(/_/g,' ').toLowerCase().replace(/^./,c=>c.toUpperCase());

            const TICK='<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2"'
              +' stroke-linecap="round" stroke-linejoin="round"><path d="m5 13 4 4L19 7"/></svg>';
            const BANG='<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2"'
              +' stroke-linecap="round" stroke-linejoin="round"><path d="M12 8v5"/>'
              +'<path d="M12 17h.01"/><circle cx="12" cy="12" r="9"/></svg>';

            async function send(file){
              out.innerHTML='';busy.classList.remove('hidden');
              drop.scrollIntoView({block:'start'});
              /*
                THE TWO FAILURES ARE KEPT APART, because they need different words. A fetch
                that fails means the lab has stopped and the terminal is where to look. A
                throw while drawing the answer means the reader worked perfectly and this page
                has a bug -- and telling somebody to go and check a healthy server is how a
                five minute fix becomes an afternoon.
              */
              let data;
              try{
                const res=await fetch('/read',{method:'POST',
                  headers:{'X-Filename':file.name},body:file});
                data=await res.json();
              }catch(e){
                busy.classList.add('hidden');
                out.innerHTML=card('Could not reach the reader',
                  'The lab has stopped, or was never started. Check the terminal window.',
                  '<p class="v none">'+esc(e)+'</p>');
                return;
              }

              try{
                render(data,file);
              }catch(e){
                out.innerHTML=card('The document was read, but this page could not show it',
                  'The reading itself worked &mdash; this is a fault in the page, not in the '
                  +'reader. The raw answer is below.',
                  '<p class="v none">'+esc(e)+'</p><details style="margin-top:12px">'
                  +'<summary>Show what the reader returned</summary><pre>'
                  +esc(JSON.stringify(data,null,2))+'</pre></details>');
              }finally{busy.classList.add('hidden');}
            }

            function card(title,why,body){
              return '<div class="card"><h2>'+title+'</h2>'
                +(why?'<p class="why">'+why+'</p>':'')+body+'</div>';
            }

            function render(r,file){
              if(r.error){
                out.innerHTML='<div class="verdict bad"><div class="badge">'+BANG+'</div><div>'
                  +'<h2>That file could not be read</h2><p>'+esc(r.error)+'</p></div></div>';
                return;
              }

              let html='';

              /* ---- the headline: what is this? ---- */
              if(r.typeLabel){
                html+='<div class="verdict ok"><div class="badge">'+TICK+'</div><div>'
                  +'<h2>'+esc(r.typeLabel)+'</h2>'
                  +'<p>Recognised from these words on the page:</p>'
                  +'<div class="chips">'+r.because.map(m=>'<span class="chip">'+esc(m.trim())
                  +'</span>').join('')+'</div></div></div>';
              }else{
                html+='<div class="verdict warn"><div class="badge">'+BANG+'</div><div>'
                  +'<h2>Not a document this reader knows</h2>'
                  +'<p>'+(r.because.length
                      ? 'Only these words were recognised, which is too few to be sure: '
                        +esc(r.because.join(', '))+'.'
                      : 'None of the words that mark a known document appeared on the page.')
                  +' It may still be readable &mdash; see what was found below.</p>'
                  +'</div></div>';
              }

              /* ---- the document announcing itself ---- */
              if(r.heading){
                html+=card('What is printed at the top of the page',
                  'Taken from the document itself, not worked out. If this is garbled, the '
                  +'reader struggled with the whole page.',
                  '<p class="quote">'+esc(r.heading)+'</p>');
              }

              /* ---- the fields this kind of document has ---- */
              const fields=Object.entries(r.fields||{});
              if(fields.length){
                let rows='',got=0;
                for(const [k,v] of fields){
                  if(v!==null) got++;
                  rows+='<div class="row"><span class="k">'+esc(k)+'</span>'
                    +(v===null?'<span class="v none">not read</span>'
                              :'<span class="v">'+esc(v)+'</span>')+'</div>';
                }
                html+=card('<span class="count">'+got+' of '+fields.length+'</span>'
                  +'What this document should tell us',
                  'Only the questions a '+esc(r.typeLabel.toLowerCase())+' can answer are asked. '
                  +'&ldquo;Not read&rdquo; means the reader could not make it out, not that the '
                  +'page lacks it &mdash; in the portal the customer types those in.', rows);
              }

              /* ---- anything at all ---- */
              const also=Object.entries(r.anything||{});
              if(also.length){
                let rows='';
                for(const [k,v] of also){
                  rows+='<div class="row"><span class="k">'+esc(k)+'</span>'
                    +'<span class="v">'+esc(v)+'</span></div>';
                }
                html+=card('Also on the page',
                  'Dates, amounts and contact details, picked up whatever the document is.',
                  rows);
              }

              /* ---- the reading itself ---- */
              html+=card('The raw reading',
                'Everything the reader produced, before any pattern was applied. Worth a look '
                +'when a field comes back empty.',
                '<p class="stats"><span><b>'+esc(r.kindOfFile)+'</b> &middot; '+r.pages
                +' page'+(r.pages===1?'':'s')+'</span><span>Took <b>'
                +(r.millis/1000).toFixed(1)+'s</b></span><span>Size <b>'
                +Math.round(r.bytes/1024).toLocaleString()+' KB</b></span></p>'
                +'<details style="margin-top:14px"><summary>Show the text</summary><pre>'
                +esc(r.text||'(nothing)')+'</pre></details>');

              html+='<div style="text-align:center"><span class="again" id="again">'
                +'Read another document</span></div>';

              out.innerHTML=html;

              const again=document.getElementById('again');
              if(again) again.addEventListener('click',()=>input.click());
            }
            </script>
            </body>
            </html>
            """;
    }
}
