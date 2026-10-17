(function () {
  if (window.__asl) return;
  var cap = null;
  try {
    var op = HTMLMediaElement.prototype.play;
    HTMLMediaElement.prototype.play = function () {
      cap = this;
      try { if (window.__aslVol != null) this.volume = window.__aslVol; } catch (e) {}
      return op.apply(this, arguments);
    };
  } catch (e) {}
  try {
    var st = document.createElement('style');
    st.textContent = 'html{filter:invert(1) hue-rotate(180deg);background:#fff}img,picture,video,canvas{filter:invert(1) hue-rotate(180deg)}';
    (document.head || document.documentElement).appendChild(st);
  } catch (e) {}
  function walk(root, fn) {
    var all = root.querySelectorAll('*');
    for (var i = 0; i < all.length; i++) { fn(all[i]); if (all[i].shadowRoot) walk(all[i].shadowRoot, fn); }
  }
  function medias() {
    var r = [];
    walk(document, function (e) { if (e instanceof HTMLMediaElement) r.push(e); });
    if (cap && r.indexOf(cap) < 0) r.unshift(cap);
    return r;
  }
  function media() {
    var m = medias();
    for (var i = 0; i < m.length; i++) if (m[i].currentSrc || m[i].src || m[i].srcObject) return m[i];
    return m[0] || null;
  }
  function mk() { try { return window.MusicKit && MusicKit.getInstance ? MusicKit.getInstance() : null; } catch (e) { return null; } }
  function label(b) {
    return ((b.getAttribute('aria-label') || '') + ' ' + (b.getAttribute('title') || '') + ' ' +
      (b.getAttribute('data-testid') || '') + ' ' + (typeof b.className === 'string' ? b.className : '')).toLowerCase();
  }
  function visible(e) { var r = e.getBoundingClientRect(); return r.width > 4 && r.height > 4; }
  function find(test) {
    var f = null;
    walk(document, function (e) {
      if (f) return;
      if (e.tagName === 'BUTTON' || (e.getAttribute && e.getAttribute('role') === 'button')) {
        if (visible(e) && test(label(e), (e.textContent || '').trim().toLowerCase())) f = e;
      }
    });
    return f;
  }
  function playBtn() { return find(function (l) { return /(^|[^a-z])play([^a-z]|$)/.test(l) && !/playlist|play next|play later|shuffle/.test(l); }); }
  function pauseBtn() { return find(function (l) { return /pause/.test(l); }); }
  function signBtn() { return find(function (l, t) { return /sign[ -]?in/.test(l) || /^sign[ -]?in$/.test(t); }); }
  function rect(b) { if (!b) return ''; var r = b.getBoundingClientRect(); return JSON.stringify({ x: r.left + r.width / 2, y: r.top + r.height / 2 }); }
  window.__asl = {
    play: function () {
      var b = playBtn(); if (b) { b.click(); return 'btn'; }
      var m = mk(); if (m) { try { m.play(); return 'mk'; } catch (e) {} }
      var a = media(); if (a) { try { a.play(); return 'media'; } catch (e) {} }
      return 'none';
    },
    playRect: function () { return rect(playBtn()); },
    signRect: function () { return rect(signBtn()); },
    pause: function () {
      var done = false, m = mk();
      if (m) { try { m.pause(); done = true; } catch (e) {} }
      medias().forEach(function (a) { try { if (!a.paused) { a.pause(); done = true; } } catch (e) {} });
      if (!done) { var b = pauseBtn(); if (b) b.click(); }
      return 'ok';
    },
    resume: function () {
      var m = mk(); if (m) { try { m.play(); return 'mk'; } catch (e) {} }
      var a = media(); if (a && a.currentSrc) { try { a.play(); return 'media'; } catch (e) {} }
      var b = playBtn(); if (b) { b.click(); return 'btn'; }
      return 'none';
    },
    vol: function (v) {
      window.__aslVol = v;
      var m = mk(); if (m) { try { m.volume = v; } catch (e) {} }
      medias().forEach(function (a) { try { a.volume = v; } catch (e) {} });
      return 'ok';
    },
    signin: function () {
      var b = signBtn(); if (b) { b.click(); return 'btn'; }
      var m = mk(); if (m && m.authorize) { try { m.authorize(); return 'mk'; } catch (e) {} }
      return 'none';
    },
    status: function () {
      var m = mk(), a = media(), s = {};
      s.mk = !!m;
      s.auth = m ? !!m.isAuthorized : null;
      s.st = m ? m.playbackState : -1;
      s.t = a ? a.currentTime : (m ? m.currentPlaybackTime : 0);
      s.d = a ? a.duration : (m ? m.currentPlaybackDuration : 0);
      if (!isFinite(s.d)) s.d = 0;
      s.playing = !!((m && m.playbackState === 2) || (a && !a.paused && !a.ended && a.currentTime > 0));
      s.ended = !!((a && a.ended) || (m && (m.playbackState === 5 || m.playbackState === 10)));
      try { s.title = (m && m.nowPlayingItem && m.nowPlayingItem.title) || document.title || ''; } catch (e) { s.title = document.title || ''; }
      s.sign = !!signBtn();
      s.pb = !!playBtn();
      s.media = !!a;
      return JSON.stringify(s);
    }
  };
})();
