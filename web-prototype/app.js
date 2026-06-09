/* ============================================================================
   HealthBridge — App shell: router, transitions, shared UI primitives
   ========================================================================== */
(function (HB) {
  const stage = () => document.getElementById('stage');
  let stack = [];          // [{name, params}]
  let animating = false;

  /* ---- tiny DOM helper ----------------------------------------------------- */
  function h(tag, attrs, children) {
    const el = document.createElement(tag);
    if (attrs) for (const k in attrs) {
      if (k === 'class') el.className = attrs[k];
      else if (k === 'html') el.innerHTML = attrs[k];
      else if (k.startsWith('on') && typeof attrs[k] === 'function') el.addEventListener(k.slice(2), attrs[k]);
      else if (k === 'dataset') Object.assign(el.dataset, attrs[k]);
      else if (attrs[k] != null && attrs[k] !== false) el.setAttribute(k, attrs[k]);
    }
    if (children != null) (Array.isArray(children) ? children : [children]).forEach(c => {
      if (c == null || c === false) return;
      el.appendChild(typeof c === 'string' ? document.createTextNode(c) : c);
    });
    return el;
  }
  HB.h = h;

  /* ---- app bar ------------------------------------------------------------- */
  // opts: { back: bool|fn, title, action: {icon, onClick, label} }
  HB.appbar = function (opts) {
    const left = opts.back
      ? h('button', { class: 'appbar__btn', 'aria-label': 'Back',
          onclick: () => (typeof opts.back === 'function' ? opts.back() : HB.back()),
          html: HB.icon('back') })
      : h('div', { class: 'appbar__btn appbar__btn--spacer' });
    const right = opts.action
      ? h('button', { class: 'appbar__btn', 'aria-label': opts.action.label || 'Action',
          onclick: opts.action.onClick, html: HB.icon(opts.action.icon) })
      : h('div', { class: 'appbar__btn appbar__btn--spacer' });
    return h('div', { class: 'appbar' }, [left, h('div', { class: 'appbar__title' }, opts.title || ''), right]);
  };

  /* ---- screen scaffold ----------------------------------------------------- */
  // Build a .screen with optional appbar / body / footer. onMount runs post-transition.
  HB.screen = function ({ appbar, body, footer, onMount, pad = true }) {
    const el = h('div', { class: 'screen' });
    if (appbar) el.appendChild(appbar);
    const bodyEl = h('div', { class: 'screen__body' + (pad ? '' : ' nopad') });
    bodyEl.classList.add('mount');
    (Array.isArray(body) ? body : [body]).forEach(c => c && bodyEl.appendChild(c));
    el.appendChild(bodyEl);
    if (footer) {
      const f = h('div', { class: 'screen__footer' });
      (Array.isArray(footer) ? footer : [footer]).forEach(c => c && f.appendChild(c));
      el.appendChild(f);
    }
    if (onMount) el._onMount = onMount;
    return el;
  };

  /* ---- ripple + press feedback (event delegation) -------------------------- */
  function attachRipple(root) {
    root.addEventListener('pointerdown', (e) => {
      const t = e.target.closest('.btn, .drow, .srow, .dropzone, .permpill');
      if (!t) return;
      const r = t.getBoundingClientRect();
      const size = Math.max(r.width, r.height) * 1.1;
      const rip = h('span', { class: 'ripple' });
      rip.style.width = rip.style.height = size + 'px';
      rip.style.left = (e.clientX - r.left - size / 2) + 'px';
      rip.style.top = (e.clientY - r.top - size / 2) + 'px';
      const prevPos = getComputedStyle(t).position;
      if (prevPos === 'static') t.style.position = 'relative';
      t.appendChild(rip);
      rip.addEventListener('animationend', () => rip.remove());
    });
  }

  /* ---- transitions --------------------------------------------------------- */
  function mount(newEl, dir) {
    const st = stage();
    const oldEl = st.querySelector('.screen.active') || st.querySelector('.screen');
    newEl.classList.add(dir === 'back' ? 'enter-left' : 'enter-right');
    st.appendChild(newEl);
    // force reflow so the enter state is committed before we animate to active
    void newEl.offsetWidth;
    animating = true;

    // commit on a fresh task (reliable across browsers + headless) so the
    // class change from enter→active triggers the CSS transition.
    setTimeout(() => {
      newEl.classList.remove('enter-left', 'enter-right');
      newEl.classList.add('active');
      if (oldEl && oldEl !== newEl) {
        oldEl.classList.remove('active');
        oldEl.classList.add(dir === 'back' ? 'exit-right' : 'exit-left');
      }
    }, 20);

    const done = () => {
      animating = false;
      if (oldEl && oldEl !== newEl && oldEl.parentNode) oldEl.remove();
      if (newEl._onMount) { try { newEl._onMount(newEl); } catch (e) { console.error(e); } }
    };
    // run onMount slightly into the transition so animations feel responsive
    if (newEl._onMount) setTimeout(() => { try { newEl._onMount(newEl); } catch (e) { console.error(e); } newEl._onMount = null; }, 60);
    let settled = false;
    const settle = () => { if (settled) return; settled = true; done(); };
    newEl.addEventListener('transitionend', settle, { once: true });
    setTimeout(settle, 420); // fallback
  }

  function render(name, params, dir) {
    const factory = HB.screens[name];
    if (!factory) { console.error('No screen:', name); return; }
    const el = factory(params || {});
    el.dataset.screen = name;
    mount(el, dir);
  }

  HB.isAnimating = () => animating;

  HB.go = function (name, params) {
    if (animating) return;
    stack.push({ name, params });
    render(name, params, 'forward');
  };
  HB.replace = function (name, params) {
    if (animating) return;
    stack[stack.length - 1] = { name, params };
    render(name, params, 'forward');
  };
  HB.reset = function (name, params) {
    stack = [{ name, params }];
    render(name, params, 'forward');
  };
  HB.back = function () {
    if (animating || stack.length <= 1) return;
    stack.pop();
    const prev = stack[stack.length - 1];
    render(prev.name, prev.params, 'back');
  };

  /* ---- bottom sheet -------------------------------------------------------- */
  // opts: { title, body, actions:[{label, kind, onClick}] }  kind: primary|ghost|danger|text
  HB.sheet = function (opts) {
    const host = document.getElementById('sheet-host');
    const close = () => {
      host.classList.remove('is-open');
      setTimeout(() => { host.innerHTML = ''; host.setAttribute('aria-hidden', 'true'); }, 320);
    };
    const actions = (opts.actions || []).map(a =>
      h('button', { class: 'btn btn--block btn--' + (a.kind || 'ghost'),
        onclick: () => { if (a.onClick) a.onClick(); if (a.keepOpen !== true) close(); } }, a.label));
    const sheet = h('div', { class: 'sheet' }, [
      h('div', { class: 'sheet__grip' }),
      opts.title && h('div', { class: 'sheet__title' }, opts.title),
      opts.body && h('div', { class: 'sheet__body', html: opts.body }),
      opts.content || null,                       // optional rich DOM (e.g. file picker rows)
      actions.length ? h('div', { class: 'sheet__actions' }, actions) : null,
    ]);
    host.innerHTML = '';
    host.appendChild(h('div', { class: 'scrim', onclick: close }));
    host.appendChild(sheet);
    host.setAttribute('aria-hidden', 'false');
    void host.offsetWidth;
    host.classList.add('is-open');
    HB._closeSheet = close;
    return close;
  };

  /* ---- toast --------------------------------------------------------------- */
  HB.toast = function (msg, icon = 'check') {
    const host = document.getElementById('toast-host');
    const t = h('div', { class: 'toast' }, [
      h('span', { html: HB.icon(icon) }), h('span', {}, msg),
    ]);
    host.appendChild(t);
    setTimeout(() => { t.classList.add('out'); t.addEventListener('animationend', () => t.remove()); }, 2200);
  };

  /* ---- animated integer counter (rAF) -------------------------------------- */
  // Eases from `from` to `to` over `ms`, calling cb(value) each frame.
  HB.countTo = function (from, to, ms, cb, done) {
    const start = performance.now();
    const delta = to - from;
    function frame() {
      const p = Math.min((performance.now() - start) / ms, 1);
      const eased = 1 - Math.pow(1 - p, 3); // easeOutCubic
      cb(Math.round(from + delta * eased));
      if (p < 1) setTimeout(frame, 16); else if (done) done();
    }
    frame();
  };

  /* ---- live status-bar clock ----------------------------------------------- */
  function tickClock() {
    const el = document.getElementById('sb-time');
    if (!el) return;
    const d = new Date();
    let hr = d.getHours(); const min = d.getMinutes();
    const h12 = ((hr + 11) % 12) + 1;
    el.textContent = h12 + ':' + String(min).padStart(2, '0');
  }

  /* ---- demo / deep-link bootstrap -----------------------------------------
     URL params let you jump straight to any screen and pre-seed sync state —
     handy for demos, screenshots and QA. Examples:
       index.html                         → onboarding (first run)
       index.html?screen=home&state=synced
       index.html?screen=delta&file=newer&state=synced   (partial delta)
       index.html?screen=settings&state=synced                              */
  function parseQuery() {
    const q = {};
    location.search.slice(1).split('&').forEach(p => {
      if (!p) return; const [k, v] = p.split('=');
      q[decodeURIComponent(k)] = decodeURIComponent(v || '');
    });
    return q;
  }
  function applyDemoState(q) {
    if (!q.state) return;
    HB.state.onboarded = true;
    HB.engine.grantAllPermissions();
    if (q.state === 'synced' || q.state === 'partial') {
      const d = HB.engine.computeDelta('main');     // simulate a completed first full sync
      HB.engine.commitSync('main', d, 5200);
    }
  }

  /* ---- boot ---------------------------------------------------------------- */
  HB.boot = function () {
    attachRipple(stage());
    tickClock(); setInterval(tickClock, 15000);
    const q = parseQuery();
    applyDemoState(q);
    const first = q.screen || (HB.state.onboarded ? 'home' : 'onboarding');
    const params = {};
    if (q.file) params.fileId = q.file;
    if (['delta', 'processing', 'writing'].includes(first) && !params.fileId) params.fileId = 'main';
    stack = [{ name: first, params }];
    render(first, params, 'forward');
  };

})(window.HB = window.HB || {});
