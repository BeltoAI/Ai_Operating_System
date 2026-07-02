  // Brand-icon fallback: some logos (LinkedIn, Instagram, Messenger…) were pulled from the icon CDN,
  // so any <img> that fails to load is swapped for a clean lettered tile — no broken images, ever.
  (function(){
    var FB = { linkedin:'in', instagram:'IG', messenger:'M', facebook:'f', x:'𝕏', whatsapp:'WA', telegram:'TG',
      reddit:'R', signal:'S', spotify:'♪', gmail:'@', googlemaps:'◎', googlecalendar:'31', googlemessages:'✉',
      tinder:'♥', uber:'Uber', notion:'N', venmo:'venmo', doordash:'D' };
    function fbFor(img){
      if (img.dataset.fbDone) return; img.dataset.fbDone = '1';
      var m = (img.src || '').match(/simpleicons\.org\/([a-z0-9]+)/i);
      var slug = m ? m[1].toLowerCase() : '';
      var t = img.getAttribute('data-fb') || FB[slug] || (img.getAttribute('alt') || '').slice(0, 2) || '•';
      var s = document.createElement('span'); s.className = 'icofb'; s.textContent = t;
      if (img.parentNode) img.replaceWith(s);
    }
    document.addEventListener('error', function(e){ var img = e.target; if (img && img.tagName === 'IMG' && /simpleicons\.org/.test(img.src || '')) fbFor(img); }, true);
    function sweep(){ document.querySelectorAll('img[src*="simpleicons.org"]').forEach(function(img){ if (img.complete && img.naturalWidth === 0) fbFor(img); }); }
    sweep(); setTimeout(sweep, 1500); setTimeout(sweep, 4000); setTimeout(sweep, 8000);
  })();

  // Minimal scroll-reveal: fade elements up as they enter view (motion-safe; one-shot).
  if (document.documentElement.classList.contains('anim') && 'IntersectionObserver' in window) {
    var reveal = document.querySelectorAll('.frow, .imports, .row2, .fbform, .fbwrap, .stats, .apps, .vbody, section > .eyebrow, section > h2, section > .muted');
    var io = new IntersectionObserver(function(ents){
      ents.forEach(function(en){ if (en.isIntersecting){ en.target.classList.add('in'); io.unobserve(en.target); } });
    }, { threshold: 0.12, rootMargin: '0px 0px -8% 0px' });
    reveal.forEach(function(e){ io.observe(e); });
  }

  // ── Build the real bottom nav (5 panels, brain in the centre) into every phone mockup ──
  (function(){
    var ICONS = {
      home:'<path d="M3 11l9-8 9 8"/><path d="M5 9.5V20h5v-6h4v6h5V9.5"/>',
      msg:'<path d="M4 5h16v11H9l-4 4V16H4z"/>',
      brain:'<circle cx="12" cy="7.5" r="2.1"/><circle cx="7" cy="15" r="2.1"/><circle cx="17" cy="15" r="2.1"/><path d="M11 9L8.4 13M13 9l2.6 4M9 15h6"/>',
      apps:'<rect x="4.5" y="4.5" width="6" height="6" rx="1.4"/><rect x="13.5" y="4.5" width="6" height="6" rx="1.4"/><rect x="4.5" y="13.5" width="6" height="6" rx="1.4"/><rect x="13.5" y="13.5" width="6" height="6" rx="1.4"/>',
      me:'<circle cx="12" cy="8" r="3.2"/><path d="M5.5 20c0-3.6 2.9-6.5 6.5-6.5S18.5 16.4 18.5 20"/>'
    };
    var ORDER = ['home','msg','brain','apps','me'];
    document.querySelectorAll('.navbar').forEach(function(bar){
      var active = bar.getAttribute('data-active');
      bar.innerHTML = ORDER.map(function(k){
        var cls = 'ni' + (k === 'brain' ? ' brain' : '') + (k === active ? ' on' : '');
        return '<span class="' + cls + '"><svg viewBox="0 0 24 24">' + ICONS[k] + '</svg></span>';
      }).join('');
    });
  })();

  // ── Animated hero: types a command, zooms in, then shows it execute ──
  (function(){
    var t = document.getElementById('heroType'), r = document.getElementById('heroResults'), stage = document.getElementById('heroStage');
    if (!t || !r) return;
    var cmd = 'create a Google Meet tomorrow 4pm with Anna, then post on LinkedIn about edge-AI inference';
    var res = '<div class="rcard"><span class="ck">✓</span><div><b>Google Meet</b> · tomorrow 4:00 PM — Anna &amp; you, invite sent</div></div>' +
              '<div class="rcard"><span class="ck">✓</span><div><b>Posted to LinkedIn</b> — “Why edge-AI inference wins”</div></div>';
    if (!matchMedia('(prefers-reduced-motion: no-preference)').matches) { t.textContent = cmd; r.innerHTML = res; r.classList.add('show'); return; }
    var sleep = function(ms){ return new Promise(function(x){ setTimeout(x, ms); }); };
    (async function loop(){
      while (true) {
        t.textContent = ''; r.innerHTML = ''; r.classList.remove('show'); if (stage) stage.classList.remove('zoom');
        await sleep(1300);
        if (stage) stage.classList.add('zoom');                 // zoom into the prompt
        for (var k = 1; k <= cmd.length; k++) { t.textContent = cmd.slice(0, k); await sleep(33); }
        await sleep(600);
        r.innerHTML = '<div class="working">✦ working…</div>'; r.classList.add('show');
        await sleep(1100);
        if (stage) stage.classList.remove('zoom');               // zoom back out…
        r.innerHTML = res;                                        // …to reveal what it did
        await sleep(3600);
      }
    })();
  })();

  // ── Staged demo animations (each mirrors the real feature; static fallback for reduced-motion) ──
  (function(){
    var motion = matchMedia('(prefers-reduced-motion: no-preference)').matches;
    var $ = function(id){ return document.getElementById(id); };
    var sleep = function(ms){ return new Promise(function(r){ setTimeout(r, ms); }); };
    async function type(el, txt, sp){ el.textContent = ''; for (var i = 1; i <= txt.length; i++){ el.textContent = txt.slice(0, i); await sleep(sp || 32); } }

    // start each demo only when its phone scrolls into view
    function whenVisible(el, cb){
      if (!el) return;
      if (!('IntersectionObserver' in window)) { cb(); return; }
      var io = new IntersectionObserver(function(ents){ ents.forEach(function(e){ if (e.isIntersecting){ io.disconnect(); cb(); } }); }, { threshold: 0.3 });
      io.observe(el);
    }
    function run(id, fn){ var el = $(id); if (el) whenVisible(el.closest('.phone'), fn); }

    // content
    var MAP = '<div class="mapmini"><svg viewBox="0 0 200 56"><rect width="200" height="56" fill="#e9efe6"/><path d="M0 37H200M64 0V56M132 0V56" stroke="#d6ddd0" stroke-width="7"/><path d="M22 47Q66 41 92 29T172 12" stroke="#F0703A" stroke-width="4" fill="none" stroke-linecap="round"/><circle cx="172" cy="12" r="5" fill="#F0703A"/><circle cx="22" cy="47" r="4" fill="#1A1714"/></svg></div>';
    var d1cmd = 'play my focus playlist, text mom I’m late, navigate home';
    var d1res = '<div class="rcard"><span class="ck">✓</span><div>Spotify — playing Focus</div></div>' +
                '<div class="rcard"><span class="ck">✓</span><div>Texted Mom — “running late”</div></div>' +
                '<div class="rcard"><span class="ck">✓</span><div>Google Maps — routing home' + MAP + '</div></div>';
    var d2q = 'what did Papa say about the trip?';
    var d2ansHtml = '<b>Papa</b> — flights booked for the 14th, landing 9am. He asked you to bring the charger 🔌';
    var d3reply = 'yeah locked in 🙏 friday 2pm works — i’ll send the deck the morning of 👀';
    var d4paper = '<div class="paper"><div class="pt">On Edge-AI Inference</div><div class="au">E. Shirokikh · Belto</div><div class="phead">Abstract</div><div class="pln m"></div><div class="pln"></div><div class="pln s"></div><div class="phead">1&nbsp;&nbsp;Introduction</div><div class="pln"></div><div class="pln m"></div><div class="phead">2&nbsp;&nbsp;Method</div><div class="pln"></div><div class="pln s"></div></div>';
    var d5opener = 'Hey Sarah — been a minute! Saw Accel’s edge-AI thesis 👀 would love to compare notes.';
    var d6calc = '<div class="calc-disp">$ 86.40</div><div class="calc-grid"><span>15%</span><span class="acc">18%</span><span>20%</span><span>$72</span><span>split 4</span><span>=</span></div>';
    var chans = [
      { slug:'whatsapp', color:'#25D366', who:'Anna · WhatsApp',      msg:'you free this weekend?', st:'✓ replied as you' },
      { slug:'gmail',    color:'#EA4335', who:'Investor · Email',     msg:'can we reschedule?',     st:'✓ drafted as you' },
      { slug:'linkedin', color:'#0A66C2', who:'Recruiter · LinkedIn', msg:'open to a chat?',        st:'✓ replied' },
      { slug:'x',        color:'#0d0d0d', who:'@troll · X',           msg:'your take is mid 🥱',    st:'✓ clapback 🔥' }
    ];
    function chanRow(c, show){ return '<div class="chanrow' + (show ? ' show' : '') + '"><div class="ci" style="background:' + c.color + '"><img src="https://cdn.simpleicons.org/' + c.slug + '/white"></div><div class="ct"><b>' + c.who + '</b><div>' + c.msg + '</div></div><div class="cs">' + (show ? c.st : '') + '</div></div>'; }

    if (!motion) {  // static end-states so reduced-motion visitors still see full mockups
      if ($('d1type')) { $('d1type').textContent = d1cmd; $('d1res').innerHTML = d1res; $('d1res').classList.add('show'); }
      if ($('d2q')) { $('d2q').textContent = d2q; var p=$('d2path'); p.style.opacity='1'; p.style.strokeDashoffset='0'; $('d2ans').innerHTML = d2ansHtml; $('d2ans').classList.add('show'); }
      if ($('d3thread')) $('d3thread').innerHTML = '<div class="bubble them">yo you still on for the demo friday?</div><div class="bubble me">' + d3reply + '</div><div class="sent">sent ✓</div>';
      if ($('d4pdf')) { $('d4pdf').innerHTML = d4paper; $('d4pdf').querySelector('.paper').classList.add('show'); $('d4stat').innerHTML = '<span class="done">✓ Published to Zenodo · DOI 10.5281/zenodo.14872</span>'; }
      if ($('d5draft')) { $('d5draft').textContent = d5opener; $('d5draft').classList.add('show'); $('d5send').innerHTML = '<span class="sentpill">✓ Sent to Sarah</span>'; }
      if ($('d6app')) { $('d6app').innerHTML = d6calc; $('d6app').classList.add('show'); }
      if ($('d7feed')) $('d7feed').innerHTML = chans.map(function(c){ return chanRow(c, true); }).join('');
      return;
    }

    // 01 Home — voice → multi-action
    run('d1type', async function(){ var t=$('d1type'), r=$('d1res'), mic=$('d1mic'), lab=$('d1label');
      while(true){ t.textContent=''; r.innerHTML=''; r.classList.remove('show'); lab.textContent='listening…'; mic.classList.add('live');
        await sleep(1700); mic.classList.remove('live'); lab.textContent='hold to speak';
        await type(t,d1cmd,30); await sleep(450);
        r.innerHTML='<div class="working">✦ working…</div>'; r.classList.add('show'); await sleep(950);
        r.innerHTML=d1res; await sleep(4400); } });

    // 02 Brain — Ask lights the synapse path
    run('d2q', async function(){ var q=$('d2q'), path=$('d2path'), ans=$('d2ans');
      while(true){ q.textContent=''; ans.textContent=''; ans.classList.remove('show'); path.style.transition='none'; path.style.opacity='0'; path.style.strokeDashoffset='1';
        await sleep(1300); await type(q,d2q,32); await sleep(350);
        path.style.opacity='1'; path.style.transition='stroke-dashoffset 1.1s ease'; path.style.strokeDashoffset='0'; await sleep(1250);
        ans.innerHTML=d2ansHtml; ans.classList.add('show'); await sleep(3800); } });

    // 03 Reply — autonomous draft + undo
    run('d3thread', async function(){ var th=$('d3thread');
      while(true){ th.innerHTML=''; await sleep(900);
        th.innerHTML='<div class="bubble them">yo you still on for the demo friday?</div>'; await sleep(850);
        th.insertAdjacentHTML('beforeend','<div class="tag" style="align-self:flex-end">✍ drafting in your voice…</div>'); await sleep(1300);
        var tag=th.querySelector('.tag'); if(tag) tag.remove();
        var me=document.createElement('div'); me.className='bubble me'; th.appendChild(me);
        await type(me,d3reply,24); await sleep(450);
        th.insertAdjacentHTML('beforeend','<div class="sent">sent ✓ · undo 5</div>'); var u=th.querySelector('.sent');
        for(var s=4;s>=1;s--){ await sleep(650); u.textContent='sent ✓ · undo '+s; }
        await sleep(800); u.textContent='sent ✓'; await sleep(1700); } });

    // 04 Research — write a paper, then publish to Zenodo
    run('d4type', async function(){ var t=$('d4type'), pdf=$('d4pdf'), stat=$('d4stat');
      while(true){ t.textContent=''; pdf.innerHTML=''; stat.innerHTML='';
        await sleep(1200); await type(t,'write a paper on edge-AI inference, then publish to Zenodo',28); await sleep(450);
        t.textContent='✦ writing the paper…'; await sleep(1400);
        t.textContent=''; pdf.innerHTML=d4paper; var pg=pdf.querySelector('.paper'); await sleep(40); pg.classList.add('show'); await sleep(1700);
        stat.innerHTML='<span class="pub">⟳ publishing to Zenodo…</span>'; await sleep(1500);
        stat.innerHTML='<span class="done">✓ Published to Zenodo · DOI 10.5281/zenodo.14872</span>'; await sleep(3200); } });

    // 05 Reconnect — draft an opener, then send
    run('d5draft', async function(){ var pill=$('d5pill'), draft=$('d5draft'), send=$('d5send');
      while(true){ draft.textContent=''; draft.classList.remove('show'); send.innerHTML=''; if(pill) pill.textContent='Draft ready';
        await sleep(1800); if(pill) pill.textContent='drafting…'; await sleep(1000);
        draft.classList.add('show'); await type(draft,d5opener,22); await sleep(500);
        send.innerHTML='<span class="sbtn">Send ›</span>'; await sleep(1500);
        send.innerHTML='<span class="sentpill">✓ Sent to Sarah</span>'; if(pill) pill.textContent='Sent ✓'; await sleep(3000); } });

    // 06 Architect — builds a mini-app live
    run('d6type', async function(){ var t=$('d6type'), app=$('d6app');
      while(true){ t.textContent=''; app.innerHTML=''; app.classList.remove('show');
        await sleep(1300); await type(t,'build me a tip calculator',32); await sleep(450);
        t.textContent='✦ building…'; await sleep(1300); t.textContent=''; app.innerHTML=d6calc; app.classList.add('show'); await sleep(3800); } });

    // 07 Auto-reply — answers every channel in your voice
    run('d7feed', async function(){ var feed=$('d7feed');
      while(true){ feed.innerHTML=''; await sleep(700);
        for (var i=0;i<chans.length;i++){
          var c=chans[i];
          feed.insertAdjacentHTML('beforeend', chanRow(c, false));
          var row=feed.lastElementChild; await sleep(80); row.classList.add('show');
          await sleep(650); row.querySelector('.cs').textContent=c.st; await sleep(650);
        }
        await sleep(2800); } });
  })();

  // ── Vote-the-next-app leaderboard (zero-setup hosted counters; one tally per app) ──
  (function(){
    var board = document.getElementById('voteBoard'); if (!board) return;
    var C = 'https://abacus.jasoncameron.dev', NS = 'slyos-world';
    var apps = [
      { key:'tinder',   slug:'tinder',   name:'Tinder',   tag:'need a girlfriend? let the AI find one 👀', color:'#FE3C72' },
      { key:'uber',     slug:'uber',     name:'Uber',     tag:'“get me home” → on its way',                color:'#000000' },
      { key:'notion',   slug:'notion',   name:'Notion',   tag:'dump your brain into your brain',           color:'#222' },
      { key:'venmo',    slug:'venmo',    name:'Venmo',    tag:'“pay Sam $20 for dinner”',                  color:'#3D95CE' },
      { key:'doordash', slug:'doordash', name:'DoorDash', tag:'“order my usual”',                          color:'#FF3008' }
    ];
    var counts = {};
    function voted(k){ try { return localStorage.getItem('vote_' + k) === '1'; } catch (e) { return false; } }
    function setVoted(k){ try { localStorage.setItem('vote_' + k, '1'); } catch (e) {} }
    function esc(s){ var d = document.createElement('div'); d.textContent = s; return d.innerHTML; }
    function render(){
      var max = 1; apps.forEach(function(a){ if ((counts[a.key]||0) > max) max = counts[a.key]; });
      var sorted = apps.slice().sort(function(a,b){ return (counts[b.key]||0) - (counts[a.key]||0); });
      board.innerHTML = sorted.map(function(a){
        var n = counts[a.key] || 0, w = Math.round((n / max) * 100), vd = voted(a.key);
        return '<div class="vrow"><div class="vbar" style="width:' + w + '%"></div>' +
          '<div class="vemoji" style="background:' + a.color + '"><img src="https://cdn.simpleicons.org/' + a.slug + '/white" alt=""></div>' +
          '<div class="vtext"><div class="vname">' + esc(a.name) + '</div><div class="vtag">' + esc(a.tag) + '</div></div>' +
          '<div class="vcount">' + n + '</div>' +
          '<button class="vbtn ' + (vd ? 'voted' : '') + '" data-k="' + a.key + '" ' + (vd ? 'disabled' : '') + '>' + (vd ? 'Voted ✓' : 'Vote') + '</button></div>';
      }).join('');
      board.querySelectorAll('.vbtn:not(.voted)').forEach(function(btn){
        btn.addEventListener('click', function(){
          var k = btn.getAttribute('data-k'); if (voted(k)) return;
          setVoted(k); counts[k] = (counts[k] || 0) + 1; render();
          fetch(C + '/hit/' + NS + '/vote-' + k).then(function(r){ return r.json(); })
            .then(function(j){ if (j && typeof j.value === 'number') { counts[k] = j.value; render(); } }).catch(function(){});
        });
      });
    }
    render();
    apps.forEach(function(a){
      fetch(C + '/get/' + NS + '/vote-' + a.key).then(function(r){ return r.json(); })
        .then(function(j){ if (j && typeof j.value === 'number') { counts[a.key] = j.value; render(); } }).catch(function(){});
    });
  })();

  // Real usage stats, refreshed from the phone via pull_stats.sh (writes stats.json).
  fetch('/stats.json').then(function(r){ return r.json(); }).then(function(s){
    function fmt(n){ return n >= 1000 ? (n/1000).toFixed(n>=10000?0:1).replace(/\.0$/,'') + 'k' : '' + n; }
    var m = document.getElementById('msgsStat'); if (m && s.messages) m.textContent = fmt(s.messages) + '+';
    var v = document.getElementById('savedStat'); if (v && (s.savedHoursWeek != null)) v.textContent = s.savedHoursWeek + 'h';
    // Live practice-portfolio growth, pulled from the phone.
    var pg = document.getElementById('pfGrowth');
    if (pg && s.portfolioGrowth != null) { var g = Number(s.portfolioGrowth); pg.textContent = (g>=0?'+':'') + g.toFixed(1) + '%'; pg.style.color = g>=0 ? '#2E9E5B' : '#B23A2E'; }
    var pv = document.getElementById('pfValue');
    if (pv && s.portfolioValue != null) pv.textContent = Number(s.portfolioValue).toLocaleString(undefined,{maximumFractionDigits:0});
    var tr = document.getElementById('tokReply');
    if (tr && s.tokensPerResponse) tr.textContent = s.tokensPerResponse;
  }).catch(function(){});

  // Downloads counter — show the HIGHER of GitHub's real APK download_count (the true number) and the
  // live click counter (Abacus), so it never reads below your actual downloads but still ticks instantly.
  var COUNTER = 'https://abacus.jasoncameron.dev', NS = 'slyos-world', KEY = 'downloads';
  var gh = 0, ab = 0, haveAny = false;
  function showDL(){ var el = document.getElementById('dlcount'); if (!el) return; var n = Math.max(gh, ab);
    // Republishing makes a fresh GitHub release (count resets to 0), so the number could drop. Keep a
    // monotonic floor in localStorage so the displayed count never goes backwards.
    try { var floor = parseInt(localStorage.getItem('slyos_dlmax') || '0', 10) || 0; if (n < floor) { n = floor; } else if (n > floor) { localStorage.setItem('slyos_dlmax', String(n)); } } catch (e) {}
    el.textContent = haveAny ? (n > 0 ? n.toLocaleString() : '0') : '—'; }
  fetch('https://api.github.com/repos/BeltoAI/Ai_Operating_System/releases')
    .then(function(r){ return r.json(); })
    .then(function(rs){ var n = 0; (rs||[]).forEach(function(rel){ (rel.assets||[]).forEach(function(a){ if (/\.apk$/i.test(a.name)) n += (a.download_count||0); }); }); gh = n; haveAny = true; showDL(); })
    .catch(function(){ showDL(); });
  fetch(COUNTER + '/get/' + NS + '/' + KEY)
    .then(function(r){ return r.json(); })
    .then(function(j){ if (j && typeof j.value === 'number') { ab = j.value; haveAny = true; showDL(); } })
    .catch(function(){});

  // Each download click ticks the live counter (and the display, if it now leads GitHub's count).
  document.querySelectorAll('a[href*="SlyOS.apk"]').forEach(function(a){
    a.addEventListener('click', function(){
      try { fetch(COUNTER + '/hit/' + NS + '/' + KEY)
        .then(function(r){ return r.json(); })
        .then(function(j){ if (j && typeof j.value === 'number') { ab = j.value; haveAny = true; showDL(); } }); } catch (e) {}
    });
  });

  // ---- Live feedback wall (Supabase REST — paste your 2 public values below; no server code) ----
  var SB_URL = 'https://xfftheaprdedypqlcvzg.supabase.co';
  var SB_KEY = 'sb_publishable_AxUM6xdI_3L-no-9MbNsxQ__u_eLmsQ';   // publishable key — safe in the browser, guarded by RLS
  var sbReady = SB_URL.indexOf('http') === 0 && SB_KEY.length > 20;
  var rating = 0;
  var BAD = /\b(fuck|shit|bitch|cunt|nigger|faggot|asshole|retard|whore|slut)\b/i;
  function esc(t){ var d = document.createElement('div'); d.textContent = t || ''; return d.innerHTML; }

  (function(){
    var box = document.getElementById('starsIn'); if (!box) return;
    box.querySelectorAll('span').forEach(function(s){
      s.addEventListener('click', function(){
        rating = +s.getAttribute('data-v');
        box.querySelectorAll('span').forEach(function(x){ x.classList.toggle('on', +x.getAttribute('data-v') <= rating); });
      });
    });
  })();

  function loadFB(){
    if (!sbReady) return;
    fetch(SB_URL + '/rest/v1/feedback?select=name,comment&rating=gte.5&order=id.desc&limit=24',
      { headers: { apikey: SB_KEY, Authorization: 'Bearer ' + SB_KEY } })
      .then(function(r){ return r.json(); })
      .then(function(rows){
        if (!Array.isArray(rows) || !rows.length) return;
        var html = rows.map(function(f){
          return '<div class="fbq"><div class="qs">★★★★★</div><div class="qt">“' + esc(f.comment) +
                 '”</div><div class="qn">— ' + esc(f.name || 'Anonymous') + '</div></div>';
        }).join('');
        document.getElementById('fbtrack').innerHTML = html + html;  // duplicate → seamless loop
      }).catch(function(){});
  }
  loadFB();

  (function(){
    var btn = document.getElementById('fbSend'); if (!btn) return;
    btn.addEventListener('click', function(){
      var msg = document.getElementById('fbMsg');
      var name = (document.getElementById('fbName').value || '').trim().slice(0, 40);
      var text = (document.getElementById('fbText').value || '').trim();
      if (!rating){ msg.textContent = 'Pick a star rating first.'; return; }
      if (text.length < 3){ msg.textContent = 'Add a short comment.'; return; }
      if (BAD.test(text) || BAD.test(name)){ msg.textContent = "Let's keep it kind 🙏"; return; }
      if (!sbReady){ msg.textContent = 'Thanks! (wall goes live once configured)'; return; }
      btn.disabled = true; msg.textContent = 'Posting…';
      fetch(SB_URL + '/rest/v1/feedback', {
        method: 'POST',
        headers: { apikey: SB_KEY, Authorization: 'Bearer ' + SB_KEY, 'Content-Type': 'application/json', Prefer: 'return=minimal' },
        body: JSON.stringify({ name: name, rating: rating, comment: text.slice(0, 280) })
      }).then(function(r){
        if (r.ok){
          msg.textContent = rating >= 5 ? 'Thanks! 🎉 yours is now on the wall' : 'Thanks for the feedback! 🙏';
          document.getElementById('fbText').value = ''; document.getElementById('fbName').value = ''; rating = 0;
          document.querySelectorAll('#starsIn span').forEach(function(s){ s.classList.remove('on'); });
          setTimeout(loadFB, 700);
        } else { msg.textContent = "Couldn't post — try again."; }
        btn.disabled = false;
      }).catch(function(){ msg.textContent = 'Network error.'; btn.disabled = false; });
    });
  })();
