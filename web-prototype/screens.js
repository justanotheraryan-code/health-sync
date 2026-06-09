/* ============================================================================
   HealthBridge — Screens (all 8, per PRD §2 inventory)
   Each entry in HB.screens is a factory(params) -> .screen element.
   ========================================================================== */
(function (HB) {
  const h = HB.h, icon = HB.icon, fmt = HB.fmt, S = HB.state, E = HB.engine, D = HB.data;

  const label = (t, cls) => h('div', { class: 'label ' + (cls || '') }, t);
  const dataTypeIcon = (id) => h('span', { class: 'drow__icon', html: icon(D.TYPE_BY_ID[id].icon) });

  HB.screens = {};

  /* ==========================================================================
     SCREEN 1 — ONBOARDING (3 slides, one-time)
     ======================================================================== */
  HB.screens.onboarding = function () {
    let i = 0;
    const el = h('div', { class: 'screen' });
    const ob = h('div', { class: 'ob' });

    const slide1 = h('div', { class: 'ob__slide is-current' }, [
      h('div', { class: 'ob__art' }, flowDiagram()),
      h('div', { class: 'h1' }, 'Your health data, your device.'),
      h('p', { class: 'lead' }, 'HealthBridge mirrors your Apple Health export to Google Health Connect — entirely on-device. No account. No cloud. No internet.'),
    ]);
    const slide2 = h('div', { class: 'ob__slide' }, [
      h('div', { class: 'ob__art' }, h('div', { class: 'flownode__box is-hero', style: 'width:96px;height:96px;border-radius:24px', html: icon('iphone') })),
      h('div', { class: 'h1' }, 'Export from your iPhone'),
      h('div', { class: 'steps-list' }, [
        stepItem(1, 'Open the <b>Health</b> app'),
        stepItem(2, 'Tap your <b>profile icon</b>, top-right'),
        stepItem(3, 'Scroll down, tap <b>Export All Health Data</b>'),
        stepItem(4, 'Save the <b>.zip</b> and transfer it to this phone'),
      ]),
    ]);
    const slide3 = h('div', { class: 'ob__slide' }, [
      h('div', { class: 'ob__art' }, h('div', { class: 'flownode__box is-hero', style: 'width:96px;height:96px;border-radius:24px', html: icon('shield') })),
      h('div', { class: 'h1' }, 'Grant Health Connect access'),
      h('p', { class: 'lead' }, 'HealthBridge writes these data types to Health Connect. You can turn any of them off later in Settings.'),
      h('div', { class: 'permgrid' }, D.TYPES.map(t =>
        h('span', { class: 'permpill' }, [h('span', { html: icon('check') }), t.label]))),
    ]);

    const slides = [slide1, slide2, slide3];
    const slidesWrap = h('div', { class: 'ob__slides' }, slides);
    const dots = h('div', { class: 'ob__dots' }, slides.map((_, k) => h('span', { class: 'ob__dot' + (k === 0 ? ' is-on' : '') })));

    const primary = h('button', { class: 'btn btn--primary btn--block', onclick: next }, 'Next');
    const skip = h('button', { class: 'btn btn--text', onclick: finish }, 'Skip');
    const foot = h('div', { class: 'ob__foot' }, [primary, skip]);

    function paint() {
      slides.forEach((s, k) => {
        s.classList.toggle('is-current', k === i);
        s.classList.toggle('is-past', k < i);
      });
      [...dots.children].forEach((d, k) => d.classList.toggle('is-on', k === i));
      primary.textContent = i === 2 ? 'Grant access & continue' : 'Next';
      skip.style.display = i === 2 ? 'none' : '';
    }
    function next() { if (i < 2) { i++; paint(); } else finish(); }
    function finish() {
      E.grantAllPermissions();
      S.onboarded = true;
      HB.reset('home');
    }

    ob.appendChild(slidesWrap); ob.appendChild(dots); ob.appendChild(foot);
    el.appendChild(ob);
    return el;

    function stepItem(n, html) {
      return h('div', { class: 'steps-list__item' }, [
        h('div', { class: 'steps-list__num' }, String(n)),
        h('div', { class: 'steps-list__txt', html }),
      ]);
    }
    function flowDiagram() {
      const node = (ic, lbl, hero) => h('div', { class: 'flownode' }, [
        h('div', { class: 'flownode__box' + (hero ? ' is-hero' : ''), html: icon(ic) }),
        h('div', { class: 'flownode__lbl' }, lbl),
      ]);
      const arrow = () => h('div', { class: 'flowarrow', html: icon('arrowRight') });
      return h('div', { class: 'flowdiag' }, [
        node('iphone', 'iPhone'), arrow(), node('logo', 'HealthBridge', true), arrow(), node('android', 'Health Connect'),
      ]);
    }
  };

  /* ==========================================================================
     SCREEN 2 — HOME / DASHBOARD
     ======================================================================== */
  HB.screens.home = function () {
    const synced = !!S.lastSync;

    const banner = synced
      ? h('div', { class: 'card card--accent' }, [
          h('div', { class: 'banner', style: 'margin-bottom:12px' }, [
            h('span', { class: 'banner__dot' }), label('Last synced'),
          ]),
          h('div', { class: 'h2', style: 'margin-bottom:4px' }, [
            h('span', { class: 'data teal' }, fmt.int(S.lastSync.newTotal)),
            h('span', {}, ' new records written'),
          ]),
          h('div', { class: 'caption mono' }, S.lastSync.dateLabel + ' · ' + S.lastSync.timeLabel),
          h('div', { class: 'caption', style: 'margin-top:8px' }, '↳ ' + S.lastSync.filename),
        ])
      : h('div', { class: 'card card--pad' }, [
          h('div', { class: 'row', style: 'gap:12px;align-items:flex-start' }, [
            h('span', { class: 'drow__icon drow__icon--muted', html: icon('cloudOff') }),
            h('div', { class: 'grow' }, [
              h('div', { class: 'h3', style: 'margin-bottom:4px' }, 'No syncs yet'),
              h('p', { class: 'caption' }, 'Import your first Apple Health export to get started.'),
            ]),
          ]),
        ]);

    const body = [banner];

    if (synced) {
      const metric = (id, lbl) => h('div', { class: 'metric' }, [
        h('div', { class: 'metric__icon', html: icon(D.TYPE_BY_ID[id].icon) }),
        h('div', { class: 'metric__value' }, fmt.int(S.syncedCount[id])),
        h('div', { class: 'metric__label' }, lbl),
      ]);
      body.push(label('On this device', 'sec-label'));
      body.push(h('div', { class: 'metrics' }, [
        metric('workout', 'Workouts'), metric('steps', 'Steps'), metric('sleep', 'Sleep'),
      ]));
    }

    body.push(label('Activity', 'sec-label'));
    body.push(h('button', { class: 'srow', onclick: () => HB.go('history') }, [
      h('span', { class: 'drow__icon drow__icon--muted', html: icon('history') }),
      h('div', { class: 'srow__main' }, [
        h('div', { class: 'srow__title' }, 'Sync history'),
        h('div', { class: 'srow__sub' }, S.history.length ? S.history.length + ' previous sync' + (S.history.length > 1 ? 's' : '') : 'No previous syncs'),
      ]),
      h('span', { class: 'drow__chev', html: icon('chevron') }),
    ]));
    // wrap that single row as a slist for the framed look
    const historyRow = body.pop();
    body.push(h('div', { class: 'slist' }, historyRow));

    return HB.screen({
      appbar: HB.appbar({ title: 'HealthBridge', action: { icon: 'settings', label: 'Settings', onClick: () => HB.go('settings') } }),
      body,
      footer: h('button', { class: 'btn btn--primary btn--block', onclick: () => HB.go('import') }, [
        h('span', { html: icon('upload') }), 'Import Apple Health Export',
      ]),
    });
  };

  /* ==========================================================================
     SCREEN 3 — FILE IMPORT
     ======================================================================== */
  HB.screens.import = function (params) {
    let selected = (params && params.fileId) ? D.FILES.find(f => f.id === params.fileId) || null : null; // optional preselect (demo/QA)

    const region = h('div', {}); // swaps between dropzone and file card
    const cta = h('button', { class: 'btn btn--primary btn--block', disabled: true,
      onclick: () => { if (selected && selected.valid) HB.go('processing', { fileId: selected.id }); } }, 'Start processing');
    const changeBtn = h('button', { class: 'btn btn--text', style: 'display:none', onclick: openPicker }, 'Choose a different file');

    function openPicker() {
      const rows = D.FILES.map(f => h('button', { class: 'srow', onclick: () => { HB._closeSheet && HB._closeSheet(); select(f); } }, [
        h('span', { class: 'drow__icon ' + (f.valid ? '' : 'drow__icon--muted'), html: icon(f.valid ? 'zip' : 'folderOpen') }),
        h('div', { class: 'srow__main' }, [
          h('div', { class: 'srow__title' }, f.name),
          h('div', { class: 'srow__sub mono' }, f.size + ' · ' + f.modified),
        ]),
        h('span', { class: 'drow__chev', html: icon('chevron') }),
      ]));
      HB.sheet({
        title: 'Select a file',
        content: h('div', { class: 'slist', style: 'margin-bottom:8px' }, rows),
        actions: [{ label: 'Cancel', kind: 'text' }],
      });
    }

    function select(f) {
      selected = f;
      paint();
    }

    function paint() {
      region.innerHTML = '';
      if (!selected) {
        region.appendChild(h('button', { class: 'dropzone', onclick: openPicker }, [
          h('div', { class: 'dropzone__icon', html: icon('upload') }),
          h('div', { class: 'h3' }, 'Select your export'),
          h('p', { class: 'caption', style: 'max-width:240px' }, 'Choose the Apple Health .zip you transferred from your iPhone.'),
        ]));
        cta.disabled = true;
        changeBtn.style.display = 'none';
        return;
      }
      // file selected
      region.appendChild(h('div', { class: 'card card--pad' }, [
        h('div', { class: 'filecard' }, [
          h('span', { class: 'filecard__icon', html: icon('zip') }),
          h('div', { class: 'grow' }, [
            h('div', { class: 'h3', style: 'word-break:break-all' }, selected.name),
            h('div', { class: 'caption mono', style: 'margin-top:4px' }, selected.size + ' · modified ' + selected.modified),
          ]),
        ]),
      ]));
      if (selected.valid) {
        region.appendChild(h('div', { style: 'margin-top:12px' },
          h('span', { class: 'chip chip--ok' }, [h('span', { html: icon('check') }), 'Valid Apple Health export · export.xml found'])));
        cta.disabled = false;
      } else {
        // US-06 validation error, exact PRD copy
        region.appendChild(h('div', { class: 'inline-error', style: 'margin-top:12px' }, [
          h('span', { html: icon('alert') }),
          h('p', {}, "This doesn't look like an Apple Health export. Export from: iPhone Health app → Profile icon → Export All Health Data."),
        ]));
        cta.disabled = true;
      }
      changeBtn.style.display = '';
    }

    paint();

    return HB.screen({
      appbar: HB.appbar({ title: 'Import export', back: true }),
      body: [region],
      footer: [cta, changeBtn],
    });
  };

  /* ==========================================================================
     SCREEN 4 — PROCESSING (3 sequential stages)
     ======================================================================== */
  HB.screens.processing = function (params) {
    const fileId = params.fileId;
    const delta = E.computeDelta(fileId);
    const known = E.knownCount();

    // stepper nodes
    const steps = [
      { key: 'unpack', title: 'Unpacking', detail: 'Extracting export.xml from ZIP…' },
      { key: 'parse',  title: 'Parsing',   detail: 'Reading health records…' },
      { key: 'dedup',  title: 'Deduplication', detail: 'Checking against known records…' },
    ];
    // declared before the map because barOf/parseBlock/dedupBlock (called inside it) assign to them
    let bar1, counterEl, previewWrap;
    const stepEls = steps.map((s, idx) => {
      const node = h('div', { class: 'step__node' }, String(idx + 1));
      const titleEl = h('div', { class: 'step__title' }, s.title);
      const detailEl = h('div', { class: 'step__detail' }, s.detail);
      const stepEl = h('div', { class: 'step' }, [
        h('div', { class: 'step__rail' }, [node, h('div', { class: 'step__line' })]),
        h('div', { class: 'step__body' }, [titleEl, detailEl, s.key === 'unpack' ? barOf('unpack') : null,
          s.key === 'parse' ? parseBlock() : null, s.key === 'dedup' ? dedupBlock() : null]),
      ]);
      s.el = stepEl; s.node = node; s.titleEl = titleEl; s.detailEl = detailEl;
      return stepEl;
    });

    function barOf() { bar1 = h('div', { class: 'progress__bar' }); return h('div', { class: 'progress', style: 'margin-top:12px;max-width:220px' }, bar1); }
    function parseBlock() {
      counterEl = h('div', { class: 'counter', style: 'margin-top:10px;display:none' }, '0');
      return counterEl;
    }
    function dedupBlock() {
      previewWrap = h('div', { style: 'margin-top:12px;display:none' });
      return previewWrap;
    }

    const cancel = h('button', { class: 'btn btn--text', onclick: () => HB.back() }, 'Cancel');

    function setState(idx, st) {
      steps[idx].el.classList.remove('is-active', 'is-done');
      if (st) steps[idx].el.classList.add(st);
      if (st === 'is-done') steps[idx].node.innerHTML = icon('check');
    }

    const el = HB.screen({
      appbar: HB.appbar({ title: 'Processing' }),
      body: [
        h('p', { class: 'lead', style: 'margin-bottom:24px' }, 'Working entirely on-device. This won’t take long.'),
        h('div', { class: 'stepper' }, stepEls),
      ],
      footer: [cancel],
      onMount: run,
    });
    return el;

    function run() {
      // ---- Stage 1: Unpack (cancellable) ----
      setState(0, 'is-active');
      setTimeout(() => { bar1.style.transition = 'width 1100ms cubic-bezier(0.2,0,0,1)'; bar1.style.width = '100%'; }, 30);

      setTimeout(() => {
        setState(0, 'is-done');
        cancel.style.display = 'none'; // PRD: no cancel during Stage 2/3
        // ---- Stage 2: Parse ----
        setState(1, 'is-active');
        counterEl.style.display = '';
        const total = delta.scannedTotal;
        const typeLabels = D.TYPES.filter(t => S.enabled[t.id]);
        let li = 0;
        const cyc = setInterval(() => {
          const t = typeLabels[li % typeLabels.length];
          steps[1].detailEl.textContent = t.label + ' ' + t.unit + '…';
          li++;
        }, 360);
        HB.countTo(0, total, 2300, v => { counterEl.textContent = fmt.int(v); }, () => {
          clearInterval(cyc);
          steps[1].detailEl.textContent = fmt.int(total) + ' records scanned';
          setState(1, 'is-done');
          // ---- Stage 3: Dedup ----
          setState(2, 'is-active');
          steps[2].detailEl.textContent = 'Checking against ' + fmt.int(known) + ' known records…';
          previewWrap.style.display = '';
          const rows = delta.rows.filter(r => r.enabled);
          rows.forEach((r, k) => {
            setTimeout(() => {
              const row = h('div', { class: 'preview-row', style: `animation-delay:${k * 20}ms` }, [
                h('span', { class: 'preview-row__name' }, r.type.label),
                h('span', { class: 'preview-row__nums' }, [
                  h('span', { class: 'preview-row__new' }, fmt.int(r.newCount) + ' new'),
                  '  /  ' + fmt.int(r.skipped) + ' synced',
                ]),
              ]);
              previewWrap.appendChild(row);
            }, k * 150);
          });
          setTimeout(() => {
            setState(2, 'is-done');
            steps[2].detailEl.textContent = 'Done · ' + fmt.int(delta.newTotal) + ' new of ' + fmt.int(delta.scannedTotal);
            setTimeout(() => HB.replace('delta', { fileId }), 650);
          }, rows.length * 150 + 500);
        });
      }, 1250);
    }
  };

  /* ==========================================================================
     SCREEN 5 — DELTA REVIEW (most important)
     ======================================================================== */
  HB.screens.delta = function (params) {
    const fileId = params.fileId;
    const delta = E.computeDelta(fileId);
    const goHome = () => HB.reset('home');

    // Empty state — nothing new to sync (US-02)
    if (delta.newTotal === 0) {
      return HB.screen({
        appbar: HB.appbar({ title: 'Review', back: goHome }),
        body: [h('div', { class: 'empty', style: 'margin-top:48px' }, [
          h('div', { class: 'empty__icon', html: icon('checkCircle') }),
          h('div', { class: 'h2' }, 'Nothing new to sync'),
          h('p', { class: 'lead', style: 'max-width:260px' }, 'All records from this export are already on your device. Your Health Connect data is up to date.'),
        ])],
        footer: h('button', { class: 'btn btn--primary btn--block', onclick: goHome }, 'Done'),
      });
    }

    const rows = delta.rows.filter(r => r.newCount > 0);
    const list = h('div', { class: 'card card--flush' }, rows.map(deltaRow));

    return HB.screen({
      appbar: HB.appbar({ title: 'Review', back: goHome }),
      body: [
        h('div', {}, [
          h('div', { class: 'h1', style: 'margin-bottom:6px' }, [
            'Ready to sync ', h('span', { class: 'data teal' }, fmt.int(delta.newTotal)), ' new records',
          ]),
          h('p', { class: 'lead' }, fmt.int(delta.skippedTotal) + ' records already on this device — skipped.'),
        ]),
        label('By data type', 'sec-label'),
        list,
        h('p', { class: 'hint' }, 'Read-only preview. Nothing is written until you confirm below.'),
      ],
      footer: [
        h('button', { class: 'btn btn--primary btn--block', onclick: () => HB.go('writing', { fileId }) }, [
          h('span', { html: icon('arrowRight') }), 'Write to Health Connect',
        ]),
        h('button', { class: 'btn btn--text', onclick: goHome }, 'Cancel'),
      ],
    });

    function deltaRow(r) {
      const detail = h('div', { class: 'detail' }, h('div', { class: 'detail__inner' },
        r.items.map(it => h('div', { class: 'ditem' }, [
          h('div', { class: 'grow' }, [
            h('div', { class: 'ditem__name' }, it.title),
            it.meta ? h('div', { class: 'ditem__meta' }, it.meta) : null,
          ]),
          h('div', { class: 'ditem__val' }, it.val),
        ])).concat(r.newCount > r.items.length
          ? [h('div', { class: 'ditem' }, h('div', { class: 'ditem__meta' }, '+ ' + fmt.int(r.newCount - r.items.length) + ' more ' + r.type.unit))]
          : [])
      ));

      const row = h('button', { class: 'drow', 'aria-expanded': 'false', onclick: toggle }, [
        dataTypeIcon(r.type.id),
        h('div', { class: 'drow__main' }, [
          h('div', { class: 'drow__title' }, r.type.label),
          h('div', { class: 'drow__sub' }, r.range),
        ]),
        h('div', { class: 'drow__count' }, '+' + fmt.int(r.newCount)),
        h('span', { class: 'drow__chev', html: icon('chevron') }),
      ]);

      function toggle() {
        const open = row.getAttribute('aria-expanded') === 'true';
        row.setAttribute('aria-expanded', open ? 'false' : 'true');
        if (open) { detail.style.height = detail.scrollHeight + 'px'; void detail.offsetWidth; detail.style.height = '0px'; }
        else { detail.style.height = detail.firstChild.offsetHeight + 'px'; }
      }
      return h('div', {}, [row, detail]);
    }
  };

  /* ==========================================================================
     SCREEN 6 — WRITING
     ======================================================================== */
  HB.screens.writing = function (params) {
    const fileId = params.fileId;
    const delta = E.computeDelta(fileId);
    const total = delta.newTotal;
    const batches = Math.max(1, Math.ceil(total / 500)); // PRD: HC batch limit 500

    const bar = h('div', { class: 'progress__bar' });
    const counter = h('div', { class: 'counter' }, '0');
    const sub = h('div', { class: 'caption mono', style: 'margin-top:8px' }, 'Batch 0 / ' + batches);

    const liveBlock = h('div', { class: 'stack', style: 'gap:24px' }, [
      h('div', {}, [
        label('Writing to Health Connect'),
        h('div', { style: 'margin-top:8px' }, [counter, h('span', { class: 'lead' }, ' / ' + fmt.int(total) + ' records')]),
      ]),
      h('div', { class: 'progress', style: 'margin-top:4px' }, bar),
      sub,
      h('p', { class: 'hint' }, 'Records are written in batches of 500. Fingerprints are saved only after each batch is confirmed.'),
    ]);

    const region = h('div', {}, liveBlock);

    const done = h('button', { class: 'btn btn--primary btn--block', disabled: true, onclick: () => HB.reset('home') }, 'Done');

    const el = HB.screen({
      appbar: HB.appbar({ title: 'Writing' }),
      body: [region],
      footer: [done],
      onMount: run,
    });
    return el;

    function run() {
      const dur = Math.min(3200, Math.max(1600, total * 1.2));
      setTimeout(() => { bar.style.transition = `width ${dur}ms cubic-bezier(0.2,0,0,1)`; bar.style.width = '100%'; }, 30);
      HB.countTo(0, total, dur, v => {
        counter.textContent = fmt.int(v);
        const b = Math.min(batches, Math.max(1, Math.ceil(v / 500)));
        sub.textContent = 'Batch ' + b + ' / ' + batches;
      }, complete);
    }

    function complete() {
      // Persist: store fingerprints + sync_log (only after confirmed write)
      E.commitSync(fileId, delta, 1000 + total);
      region.innerHTML = '';
      const success = h('div', { class: 'success', style: 'margin-top:40px' }, [
        h('div', { class: 'check', html: '<svg viewBox="0 0 52 52" fill="none"><path class="check__path" d="M14 27 22 35 38 17"/></svg>' }),
        h('div', { class: 'h1' }, 'Sync complete'),
        h('p', { class: 'lead', style: 'max-width:260px' }, [
          h('span', { class: 'data teal' }, fmt.int(total)), ' records added to Health Connect.',
        ]),
        h('div', { class: 'chip chip--ok', style: 'margin-top:4px' }, [h('span', { html: icon('shield') }), 'Stored on-device only']),
      ]);
      region.appendChild(success);
      done.disabled = false;
    }
  };

  /* ==========================================================================
     SCREEN 7 — SYNC HISTORY
     ======================================================================== */
  HB.screens.history = function () {
    let body;
    if (!S.history.length) {
      body = [h('div', { class: 'empty', style: 'margin-top:48px' }, [
        h('div', { class: 'empty__icon', html: icon('history') }),
        h('div', { class: 'h2' }, 'No syncs yet'),
        h('p', { class: 'lead', style: 'max-width:240px' }, 'Your import history will appear here once you’ve written your first records.'),
      ])];
    } else {
      body = [h('div', { class: 'card card--flush' }, S.history.map(historyRow))];
    }
    return HB.screen({ appbar: HB.appbar({ title: 'Sync history', back: true }), body });

    function historyRow(entry) {
      const types = Object.entries(entry.writtenByType);
      const detail = h('div', { class: 'detail' }, h('div', { class: 'detail__inner' }, [
        ...types.map(([name, n]) => h('div', { class: 'ditem' }, [
          h('div', { class: 'ditem__name' }, name),
          h('div', { class: 'ditem__val teal' }, '+' + fmt.int(n)),
        ])),
        h('div', { class: 'ditem' }, [h('div', { class: 'ditem__meta' }, 'Skipped (already synced)'), h('div', { class: 'ditem__val' }, fmt.int(entry.skippedTotal))]),
        h('div', { class: 'ditem' }, [h('div', { class: 'ditem__meta' }, 'Duration'), h('div', { class: 'ditem__val' }, fmt.ms(entry.durationMs))]),
        h('div', { class: 'ditem' }, [h('div', { class: 'ditem__meta' }, 'Source file'), h('div', { class: 'ditem__val', style: 'max-width:150px;overflow:hidden;text-overflow:ellipsis' }, entry.filename)]),
      ]));

      const row = h('button', { class: 'drow', 'aria-expanded': 'false', onclick: toggle }, [
        h('span', { class: 'drow__icon', html: icon('check') }),
        h('div', { class: 'drow__main' }, [
          h('div', { class: 'drow__title' }, entry.dateLabel + ' · ' + entry.timeLabel),
          h('div', { class: 'drow__sub' }, entry.filename),
        ]),
        h('div', { class: 'drow__count' }, '+' + fmt.int(entry.newTotal)),
        h('span', { class: 'drow__chev', html: icon('chevron') }),
      ]);
      function toggle() {
        const open = row.getAttribute('aria-expanded') === 'true';
        row.setAttribute('aria-expanded', open ? 'false' : 'true');
        if (open) { detail.style.height = detail.scrollHeight + 'px'; void detail.offsetWidth; detail.style.height = '0px'; }
        else { detail.style.height = detail.firstChild.offsetHeight + 'px'; }
      }
      return h('div', {}, [row, detail]);
    }
  };

  /* ==========================================================================
     SCREEN 8 — SETTINGS
     ======================================================================== */
  HB.screens.settings = function () {
    const stats = E.dbStats();
    const grantedCount = D.TYPES.filter(t => S.permissions[t.id]).length;

    // --- Permissions card ---
    const permCard = h('div', { class: 'slist' }, [
      h('div', { class: 'srow' }, [
        h('span', { class: 'drow__icon', html: icon('shield') }),
        h('div', { class: 'srow__main' }, [
          h('div', { class: 'srow__title' }, 'Health Connect access'),
          h('div', { class: 'srow__sub' }, grantedCount + ' of ' + D.TYPES.length + ' data types granted'),
        ]),
        h('span', { class: 'chip ' + (grantedCount === D.TYPES.length ? 'chip--ok' : 'chip--warn') }, grantedCount + '/' + D.TYPES.length),
      ]),
      h('button', { class: 'srow', onclick: () => { E.grantAllPermissions(); HB.toast('Permissions confirmed'); HB.replace('settings'); } }, [
        h('span', { class: 'drow__icon drow__icon--muted', html: icon('reset') }),
        h('div', { class: 'srow__main' }, [h('div', { class: 'srow__title' }, 'Re-trigger permission grant')]),
        h('span', { class: 'drow__chev', html: icon('chevron') }),
      ]),
    ]);

    // --- Data type toggles (US-04) ---
    const typeList = h('div', { class: 'slist' }, D.TYPES.map(t => {
      const input = h('input', { type: 'checkbox', 'aria-label': t.label });
      if (S.enabled[t.id]) input.checked = true;
      input.addEventListener('change', () => { S.enabled[t.id] = input.checked; });
      return h('div', { class: 'srow' }, [
        h('span', { class: 'drow__icon', html: icon(t.icon) }),
        h('div', { class: 'srow__main' }, [
          h('div', { class: 'srow__title' }, t.label),
          h('div', { class: 'srow__sub mono' }, t.hc),
        ]),
        h('label', { class: 'switch' }, [input, h('span', { class: 'switch__track' }), h('span', { class: 'switch__thumb' })]),
      ]);
    }));

    // --- Fingerprint DB ---
    const dbCard = h('div', { class: 'slist' }, [
      h('div', { class: 'srow' }, [
        h('span', { class: 'drow__icon', html: icon('database') }),
        h('div', { class: 'srow__main' }, [
          h('div', { class: 'srow__title' }, 'Fingerprint database'),
          h('div', { class: 'srow__sub mono' }, fmt.int(stats.count) + ' records · ' + stats.size),
        ]),
      ]),
      h('button', { class: 'srow', onclick: confirmReset }, [
        h('span', { class: 'drow__icon', style: 'background:rgba(255,77,77,0.12);color:var(--error)', html: icon('reset') }),
        h('div', { class: 'srow__main' }, [
          h('div', { class: 'srow__title err' }, 'Reset sync history'),
          h('div', { class: 'srow__sub' }, 'Re-evaluate all records on next import'),
        ]),
        h('span', { class: 'drow__chev', html: icon('chevron') }),
      ]),
    ]);

    function confirmReset() {
      HB.sheet({
        title: 'Reset sync history?',
        body: 'This will <b>not</b> delete any data from Health Connect. It will cause all records to be re-evaluated on your next import, which may result in duplicates if records already exist in Health Connect.',
        actions: [
          { label: 'Reset', kind: 'danger', onClick: () => { E.resetFingerprints(); HB.toast('Fingerprint database cleared', 'database'); HB.replace('settings'); } },
          { label: 'Cancel', kind: 'text' },
        ],
      });
    }

    return HB.screen({
      appbar: HB.appbar({ title: 'Settings', back: true }),
      body: [
        label('Permissions', 'sec-label'), permCard,
        label('Supported data types', 'sec-label'), typeList,
        h('p', { class: 'hint' }, 'Turn a type off to skip it entirely on your next import — it won’t be parsed, fingerprinted, or written.'),
        label('Storage', 'sec-label'), dbCard,
        h('p', { class: 'hint' }, 'The database stores only hashes of type + date — never your health values.'),
        label('About', 'sec-label'),
        h('div', { class: 'card card--pad' }, [
          h('div', { class: 'row row--between' }, [h('span', { class: 'caption' }, 'Version'), h('span', { class: 'caption mono' }, '1.0.0 (MVP)')]),
          h('div', { class: 'row row--between', style: 'margin-top:8px' }, [h('span', { class: 'caption' }, 'Network access'), h('span', { class: 'chip chip--ok' }, [h('span', { html: icon('cloudOff') }), 'None'])]),
          h('p', { class: 'hint', style: 'margin-top:12px' }, 'Open source · No accounts · No analytics · 100% on-device.'),
        ]),
      ],
    });
  };

})(window.HB = window.HB || {});
