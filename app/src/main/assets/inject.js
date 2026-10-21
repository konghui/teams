(function () {
  "use strict";
  if (window.__teamsWrapperInstalled) return;
  window.__teamsWrapperInstalled = true;

  var B = window.AndroidTeams;
  function log(m) { try { B && B.log(String(m)); } catch (e) {} }
  log("inject.js installed");

  // ---------------------------------------------------------------------------
  // 0) Hide Teams' own in-page toast banners. We already mirror them to native
  //    Android notifications, so the in-app popups are redundant and obstruct
  //    the UI. opacity:0 (not display:none) keeps them laid out so our observer
  //    can still read innerText before forwarding.
  // ---------------------------------------------------------------------------
  try {
    var style = document.createElement("style");
    style.id = "teams-wrapper-hide-toasts";
    style.textContent =
      '[data-tid="in-app-toaster-container"],' +
      '[data-tid="app-layout-area--toasts"],' +
      '[data-tid="app-layout-area--in-app-notifications"]' +
      '{opacity:0!important;pointer-events:none!important;}' +
      // The desktop "window drag" header overlays the top toolbar in a WebView
      // and swallows clicks on the call controls (hang up / mic / camera).
      // We never drag a window here, so let clicks pass through.
      '[data-tid="draggable-header"]{pointer-events:none!important;}' +
      // Android WebView flashes a highlight over the whole tappable region on
      // tap; on the chat list this lights up the entire group. Kill the flash.
      '*{-webkit-tap-highlight-color:transparent!important;}';
    // NOTE: increasing chat-list row height via CSS was tried and reverted — the
    // list is virtualized (rows positioned by JS at a fixed pitch), so forcing a
    // taller row makes text overlap badly. Left at Teams' default height.
    (document.head || document.documentElement).appendChild(style);
  } catch (e) { log("hide-toast css err " + e); }

  // ---------------------------------------------------------------------------
  // Helper: forward a message to native, de-duplicated.
  // ---------------------------------------------------------------------------
  var recentMsgs = {}; // text -> timestamp
  function pushMessage(title, body, chatId) {
    title = (title || "Teams").trim();
    body = (body || "").trim();
    var key = title + "\u0001" + body;
    var now = Date.now();
    // Drop duplicates seen in the last 8s (observer fires repeatedly).
    if (recentMsgs[key] && now - recentMsgs[key] < 8000) return;
    recentMsgs[key] = now;
    try { B.onMessage(title, body, String(now), chatId || ""); } catch (e) { log("onMessage err " + e); }
  }

  // Pull the conversation ids Teams embeds in the toast (used so tapping the
  // native notification can jump to the right chat). Returns "channelId|msgId".
  function extractChatId(node) {
    try {
      var scan = [node].concat([].slice.call(node.querySelectorAll("*")));
      for (var i = 0; i < scan.length; i++) {
        var el = scan[i];
        for (var j = 0; j < el.attributes.length; j++) {
          var v = el.attributes[j].value;
          if (v.indexOf("channelId") !== -1) {
            var c = v.match(/"channelId":"([^"]+)"/);
            var mm = v.match(/"messageId":"([^"]+)"/);
            if (c) return c[1] + "|" + (mm ? mm[1] : "");
          }
        }
      }
    } catch (e) {}
    return "";
  }

  // Click the matching conversation row in the list (pure in-SPA navigation:
  // stays in the web app, no reload, never leaves to the native app).
  function clickChatByTitle(title) {
    title = (title || "").trim();
    if (!title) return false;
    var rows = document.querySelectorAll(
      '[role="treeitem"],[data-tid*="list-item" i],[data-tid*="chat-list" i] li,li'
    );
    // Pass 1: exact match on the row's first text line (the chat/sender name).
    for (var i = 0; i < rows.length; i++) {
      var first = ((rows[i].innerText || "").split("\n")[0] || "").trim();
      if (first && first === title) {
        (rows[i].querySelector('a,[role=button],button') || rows[i]).click();
        return true;
      }
    }
    // Pass 2: row whose first line starts with the title.
    for (var k = 0; k < rows.length; k++) {
      var f2 = ((rows[k].innerText || "").split("\n")[0] || "").trim();
      if (f2 && f2.indexOf(title) === 0) {
        (rows[k].querySelector('a,[role=button],button') || rows[k]).click();
        return true;
      }
    }
    return false;
  }

  // Called by native when a message notification is tapped. Prefers in-page
  // navigation (stay on the Teams web page); falls back to a deep link only if
  // the conversation isn't found in the list.
  window.__teamsWrapperOpenChat = function (chatId, title) {
    if (clickChatByTitle(title)) {
      log("opened in-page: " + title);
      return true;
    }
    var parts = (chatId || "").split("|");
    var channelId = parts[0], messageId = parts[1] || "0";
    if (channelId) {
      var url = "https://teams.microsoft.com/l/message/" +
        encodeURIComponent(channelId) + "/" + encodeURIComponent(messageId);
      log("open chat (deep link) -> " + url);
      location.assign(url);
      return true;
    }
    log("open chat: not found");
    return false;
  };

  // ---------------------------------------------------------------------------
  // 1a) In-app toast capture (the REAL path).
  //     Teams v2 does NOT use the Web Notification API while the tab is
  //     "visible" (a WebView always is). Instead it injects an in-page toast
  //     into one of the notification containers. That DOM insertion fires even
  //     when our Android app is backgrounded, so we observe it and forward.
  //     NOTE: selectors reflect current Teams markup; update if MS changes it.
  // ---------------------------------------------------------------------------
  var TOAST_HOST_SELECTOR =
    '[data-tid="in-app-toaster-container"],' +
    '[data-tid="app-layout-area--toasts"],' +
    '[data-tid="app-layout-area--in-app-notifications"],' +
    '[data-tid="app-layout-area--notifications"],' +
    '[data-tid*="toast" i],[data-tid*="notification" i]';

  var NOISE = ["Microsoft Teams", "Send a quick reply", "Dismiss", ""];

  function parseToast(el) {
    var raw = (el.innerText || "").split("\n");
    var lines = [];
    raw.forEach(function (l) {
      l = l.trim();
      if (NOISE.indexOf(l) === -1 && lines.indexOf(l) === -1) lines.push(l);
    });
    if (!lines.length) return null;
    return { title: lines[0], body: lines.slice(1).join(" \u00b7 ") };
  }

  var toastObserver = new MutationObserver(function (muts) {
    muts.forEach(function (m) {
      for (var i = 0; i < m.addedNodes.length; i++) {
        var n = m.addedNodes[i];
        if (n.nodeType !== 1) continue;
        var host = n.closest && n.closest(TOAST_HOST_SELECTOR);
        if (!host) continue;
        var parsed = parseToast(n);
        if (parsed) pushMessage(parsed.title, parsed.body, extractChatId(n));
      }
    });
  });
  toastObserver.observe(document.documentElement, { childList: true, subtree: true });

  // ---------------------------------------------------------------------------
  // 1b) Fallbacks: also hook the Web Notification API and the ServiceWorker
  //     showNotification, in case some notifications go through them.
  // ---------------------------------------------------------------------------
  try {
    var NativeNotification = window.Notification;
    function WrappedNotification(title, opts) {
      opts = opts || {};
      pushMessage(title || "Teams", opts.body || "");
      return {
        close: function () {},
        addEventListener: function () {},
        removeEventListener: function () {},
        onclick: null
      };
    }
    WrappedNotification.permission = "granted";
    WrappedNotification.requestPermission = function (cb) {
      if (cb) cb("granted");
      return Promise.resolve("granted");
    };
    if (NativeNotification) {
      WrappedNotification.prototype = NativeNotification.prototype;
    }
    window.Notification = WrappedNotification;
  } catch (e) { log("notif hook err " + e); }

  try {
    var proto = ServiceWorkerRegistration.prototype;
    var origShow = proto.showNotification;
    proto.showNotification = function (title, opts) {
      opts = opts || {};
      pushMessage(title || "Teams", opts.body || "");
      return origShow.apply(this, arguments);
    };
  } catch (e) { log("sw hook err " + e); }

  // ---------------------------------------------------------------------------
  // 2) Incoming call detection.
  //    NOTE: this relies on Teams web DOM structure (data-tid / aria-label) and
  //    WILL need updating when Microsoft changes their markup. Multiple
  //    selectors are tried for resilience.
  // ---------------------------------------------------------------------------
  // Real Teams v2 incoming-call buttons (captured live): the buttons expose an
  // aria-label and no data-tid. Prefer answering a voice call as audio.
  var ACCEPT_SELECTORS = [
    'button[aria-label="Accept with audio"]',
    'button[aria-label="Accept with video"]',
    'button[aria-label^="Accept" i]',
    'button[aria-label*="接听"]',
    '[data-tid="toast-accept-call"]',
    '[data-tid="calling-toast-accept"]'
  ];
  var DECLINE_SELECTORS = [
    'button[aria-label="Decline call"]',
    'button[aria-label^="Decline" i]',
    'button[aria-label*="拒绝"]',
    '[data-tid="toast-decline-call"]',
    '[data-tid="calling-toast-decline"]'
  ];

  function findOne(selectors) {
    for (var i = 0; i < selectors.length; i++) {
      var el = document.querySelector(selectors[i]);
      if (el) return el;
    }
    return null;
  }

  // Click the matching button, retrying for a few seconds because the call UI
  // may render slightly after the native action fires (e.g. after we bring the
  // activity to the foreground).
  function clickWithRetry(selectors, label, tries) {
    var el = findOne(selectors);
    if (el) {
      el.click();
      // Fire a pointer sequence too, in case a bare click is ignored.
      try {
        ['pointerdown', 'mousedown', 'pointerup', 'mouseup'].forEach(function (t) {
          el.dispatchEvent(new MouseEvent(t, { bubbles: true, cancelable: true }));
        });
      } catch (e) {}
      log(label + " clicked");
      return true;
    }
    if (tries > 0) {
      setTimeout(function () { clickWithRetry(selectors, label, tries - 1); }, 300);
    } else {
      log(label + ": button not found");
    }
    return false;
  }

  // Exposed so native (notification / call screen) can drive the page.
  window.__teamsWrapperAnswer = function () {
    return clickWithRetry(ACCEPT_SELECTORS, "answer", 15);
  };
  window.__teamsWrapperDecline = function () {
    return clickWithRetry(DECLINE_SELECTORS, "decline", 15);
  };

  // ---------------------------------------------------------------------------
  // 2b) In-conversation "back" so the Android back gesture/button returns to the
  //     chat list instead of doing nothing (Teams SPA keeps no URL history when
  //     opening a chat). Returns true if it handled the back navigation.
  // ---------------------------------------------------------------------------
  var BACK_SELECTORS = [
    '[data-tid="layout-navigation-controls"]',
    'button[aria-label="Return to list"]',
    'button[aria-label="Back"]',
    'button[aria-label*="返回"]'
  ];
  window.__teamsWrapperBack = function () {
    for (var i = 0; i < BACK_SELECTORS.length; i++) {
      var el = document.querySelector(BACK_SELECTORS[i]);
      if (el) {
        var r = el.getBoundingClientRect();
        if (r.width > 0 && r.height > 0) {
          el.click();
          log("back-to-list clicked");
          return true;
        }
      }
    }
    return false;
  };

  var callActive = false;
  var lastCallId = "";
  var inCall = false;

  // A connected call shows a hang-up / leave control (and a mic toggle). We use
  // that to tell native to switch Android into communication audio mode so the
  // voice uses the (louder) voice-call stream and notifications get ducked.
  var INCALL_SELECTORS = [
    'button[aria-label*="Hang up" i]',
    'button[aria-label*="Leave" i]',
    'button[aria-label*="挂断"]',
    'button[aria-label*="离开"]',
    'button[aria-label*="Mute" i]',
    'button[aria-label*="Unmute" i]'
  ];

  function guessCaller() {
    // Try common places the caller name shows up in the incoming toast.
    var sel = [
      '[data-tid="calling-toast-title"]',
      '[data-tid="toast-title"]',
      '[class*="callingToast"] [class*="title"]'
    ];
    var el = findOne(sel);
    return el ? (el.textContent || "").trim() : "";
  }

  function scan() {
    var accept = findOne(ACCEPT_SELECTORS);
    if (accept && !callActive) {
      callActive = true;
      lastCallId = String(Date.now());
      var caller = guessCaller() || "Teams 来电";
      log("incoming call: " + caller);
      try { B.onIncomingCall(caller, lastCallId); } catch (e) {}
    } else if (!accept && callActive) {
      callActive = false;
      log("call toast gone");
      try { B.onCallEnded(lastCallId); } catch (e) {}
    }

    // Connected-call (in-call) state for native audio routing.
    var connected = !!findOne(INCALL_SELECTORS);
    if (connected !== inCall) {
      inCall = connected;
      log("in-call: " + inCall);
      try { B.onCallStateChanged(inCall); } catch (e) {}
    }
  }

  var obs = new MutationObserver(function () { scan(); });
  obs.observe(document.documentElement, { childList: true, subtree: true });
  // Safety net poll in case mutations are missed.
  setInterval(scan, 1500);

  log("inject.js ready");
})();
