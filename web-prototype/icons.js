/* ============================================================================
   HealthBridge — Icon set
   Inline SVG, currentColor, 24px grid, 2px stroke. No icon-font dependency.
   Access via HB.icon('name'). Unknown names render an empty string.
   ========================================================================== */
(function (HB) {
  const S = (inner, opts = {}) =>
    `<svg viewBox="0 0 24 24" fill="${opts.fill || 'none'}" stroke="${opts.stroke || 'currentColor'}" ` +
    `stroke-width="${opts.sw || 2}" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${inner}</svg>`;

  const ICONS = {
    // --- data types ---
    workout: S('<path d="M6.5 6.5 17.5 17.5"/><path d="M3 7.5 4.5 9M4.5 4.5 9 9M15 15l4.5 4.5M19.5 16.5 21 18"/><path d="m4.5 4.5 1.5-1.5M18 19.5 19.5 21"/>'),
    steps: S('<path d="M7 14c-1.5 0-2.5-1.2-2.5-3.2C4.5 7 6 4 8 4s2.2 2.8 2 5.5C9.8 12.3 8.5 14 7 14Z" fill="currentColor" stroke="none"/><path d="M7 14c0 2 .8 2.5.5 4-.3 1.3-2 1.5-2.7.3C4 17 5 16 5.2 14.6" fill="currentColor" stroke="none"/><path d="M17 18c-1.5 0-2.8-1.7-3-4.5C13.8 10.8 15 8 17 8s3.5 3 3.5 6.8C20.5 16.8 18.5 18 17 18Z" fill="currentColor" stroke="none" opacity="0.55"/>'),
    heart: S('<path d="M12 20s-7-4.6-9.3-9C1 7.7 2.7 4.5 6 4.5c2 0 3.3 1.4 4 2.5.7-1.1 2-2.5 4-2.5 3.3 0 5 3.2 3.3 6.5C19 15.4 12 20 12 20Z" fill="currentColor" stroke="none"/><path d="M5 11.5h2.5l1.5-2 2 4 1.3-2.5h2" stroke="#04221E" stroke-width="1.6"/>'),
    sleep: S('<path d="M20 14.5A8 8 0 0 1 9.5 4 8 8 0 1 0 20 14.5Z" fill="currentColor" stroke="none"/><path d="M15 4h3l-3 3.5h3" stroke="#04221E" stroke-width="1.4" opacity="0.7"/>'),
    energy: S('<path d="M13 2 4.5 13.5H11l-1 8.5L19.5 10H13l0-8Z" fill="currentColor" stroke="none"/>'),
    resting: S('<path d="M12 20s-7-4.6-9.3-9C1 7.7 2.7 4.5 6 4.5c2 0 3.3 1.4 4 2.5.7-1.1 2-2.5 4-2.5 3.3 0 5 3.2 3.3 6.5C19 15.4 12 20 12 20Z"/><path d="M5 11h3l1.5-2.5L12 14l1.5-3H19"/>'),
    vo2: S('<path d="M12 4v6"/><path d="M12 10c-1.5-2.5-3.5-3-5-2.2C5 9 5 12.5 6.5 16c1 2.4 2.4 3.5 3.5 3 1.2-.6 1.3-2.8 1.3-5"/><path d="M12 10c1.5-2.5 3.5-3 5-2.2C19 9 19 12.5 17.5 16c-1 2.4-2.4 3.5-3.5 3-1.2-.6-1.3-2.8-1.3-5"/>'),

    // --- chrome / nav ---
    back: S('<path d="M15 5l-7 7 7 7"/>'),
    chevron: S('<path d="M9 6l6 6-6 6"/>'),
    chevronDown: S('<path d="M6 9l6 6 6-6"/>'),
    close: S('<path d="M6 6l12 12M18 6 6 18"/>'),
    settings: S('<circle cx="12" cy="12" r="3"/><path d="M19.4 13a1.7 1.7 0 0 0 .3 1.9l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.9-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 0 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.9.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.9 1.7 1.7 0 0 0-1.5-1H3a2 2 0 0 1 0-4h.1A1.7 1.7 0 0 0 4.6 9a1.7 1.7 0 0 0-.3-1.9l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.9.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 0 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.9-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.9V9a1.7 1.7 0 0 0 1.5 1H21a2 2 0 0 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1Z"/>'),
    history: S('<path d="M3 12a9 9 0 1 0 3-6.7L3 8"/><path d="M3 4v4h4"/><path d="M12 8v4l3 2"/>'),
    check: S('<path d="M5 12.5 10 17.5 19.5 7"/>'),
    checkCircle: S('<circle cx="12" cy="12" r="9"/><path d="M8 12.5l2.5 2.5L16 9"/>'),
    plus: S('<path d="M12 5v14M5 12h14"/>'),
    arrowRight: S('<path d="M5 12h14M13 6l6 6-6 6"/>'),
    alert: S('<path d="M12 3 2 20h20L12 3Z"/><path d="M12 9v5M12 17.5v.5"/>'),
    info: S('<circle cx="12" cy="12" r="9"/><path d="M12 11v5M12 8v.5"/>'),
    shield: S('<path d="M12 3 5 6v5c0 4.2 2.9 7.5 7 9 4.1-1.5 7-4.8 7-9V6l-7-3Z"/><path d="M9 12l2 2 4-4"/>'),
    database: S('<ellipse cx="12" cy="6" rx="7" ry="3"/><path d="M5 6v6c0 1.7 3.1 3 7 3s7-1.3 7-3V6"/><path d="M5 12v6c0 1.7 3.1 3 7 3s7-1.3 7-3v-6"/>'),
    reset: S('<path d="M3 12a9 9 0 1 0 2.6-6.3"/><path d="M3 4v4h4"/>'),
    upload: S('<path d="M12 16V4"/><path d="M7 9l5-5 5 5"/><path d="M4 16v2a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-2"/>'),
    file: S('<path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8l-5-5Z"/><path d="M14 3v5h5"/>'),
    zip: S('<path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8l-5-5Z"/><path d="M14 3v5h5"/><path d="M11 7h1M11 9.5h1M11 12h1" stroke-width="1.6"/><rect x="10" y="13.5" width="3" height="3.5" rx="0.6" stroke-width="1.4"/>'),
    folderOpen: S('<path d="M3 7a2 2 0 0 1 2-2h3.5l2 2H19a2 2 0 0 1 2 2v1H5l-2 8Z"/><path d="M3 19l2-9h17l-2 9Z"/>'),

    // --- flow diagram ---
    iphone: S('<rect x="7" y="2.5" width="10" height="19" rx="2.5"/><path d="M10.5 5h3" stroke-width="1.6"/>'),
    android: S('<path d="M5 11a7 7 0 0 1 14 0v6a1 1 0 0 1-1 1H6a1 1 0 0 1-1-1v-6Z"/><path d="M8 7 6.5 5M16 7l1.5-2" stroke-width="1.6"/><circle cx="9.5" cy="11" r="0.6" fill="currentColor"/><circle cx="14.5" cy="11" r="0.6" fill="currentColor"/><path d="M8 18v2.5M16 18v2.5" stroke-width="1.6"/>'),
    bridge: S('<path d="M2 9c4 0 4 3 6 3s2-3 4-3 2 3 4 3 2-3 6-3"/><path d="M3 9v9M21 9v9M8.5 12v6M15.5 12v6"/><path d="M2 18h20" stroke-width="1.6"/>'),
    logo: S('<path d="M12 21s-7-4.6-9.3-9C1 8.7 2.7 5.5 6 5.5c2 0 3.3 1.4 4 2.5.7-1.1 2-2.5 4-2.5 3.3 0 5 3.2 3.3 6.5"/><path d="M4 12h3.5l1.5-2.5L12 16l1.8-4 1.2 2.2 1-1.2H21" stroke-width="1.8"/>'),

    // --- misc ---
    lock: S('<rect x="5" y="11" width="14" height="9" rx="2"/><path d="M8 11V8a4 4 0 0 1 8 0v3"/>'),
    wifi_off: S('<path d="M2 8.8A15 15 0 0 1 22 8.8"/><path d="M2 2l20 20" stroke="var(--teal)"/>'),
    calendar: S('<rect x="4" y="5" width="16" height="16" rx="2"/><path d="M4 9h16M9 3v4M15 3v4"/>'),
    clock: S('<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/>'),
    cloudOff: S('<path d="M4 4l16 16"/><path d="M6.5 9A4.5 4.5 0 0 0 7 18h9"/><path d="M19 16a4 4 0 0 0-1.5-7.6A6 6 0 0 0 9 6"/>'),
  };

  HB.icon = (name, cls) => {
    const svg = ICONS[name] || '';
    if (!cls) return svg;
    return svg.replace('<svg ', `<svg class="${cls}" `);
  };
})(window.HB = window.HB || {});
