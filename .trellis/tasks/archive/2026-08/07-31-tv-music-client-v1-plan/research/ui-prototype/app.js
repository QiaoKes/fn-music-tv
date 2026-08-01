(function () {
  "use strict";

  var CANVAS_WIDTH = 1920;
  var CANVAS_HEIGHT = 1080;
  var PLAYER_MODE_KEY = "echo-tv-player-mode";
  var CACHE_LIMIT_KEY = "echo-tv-cache-limit";
  var CACHE_USAGE_KEY = "echo-tv-cache-usage";
  var SERVER_URL_KEY = "echo-tv-server-url";
  var KEEP_LOGIN_KEY = "echo-tv-keep-login";
  var ACTIVE_CACHE_USAGE = 18;
  var CACHE_LIMITS = [128, 256, 512, 1024];
  var VALID_ROUTES = new Set([
    "home",
    "my",
    "login",
    "playlists",
    "playlist",
    "collection",
    "settings",
    "player",
    "player-poster"
  ]);

  var app = document.getElementById("tv-app");
  var root = document.getElementById("scale-root");
  var pages = Array.from(document.querySelectorAll("[data-page]"));
  var currentFocus = null;
  var pendingFocusSelector = "";
  var pendingFocusElement = null;
  var lastHomeFocus = null;
  var routeBeforeDetail = "home";
  var routeBeforePlayer = "playlist";
  var currentRoute = "home";
  var playerMode = readPlayerMode();
  var cacheLimit = readCacheLimit();
  var cacheUsage = readCacheUsage(cacheLimit);
  var rememberedServerUrl = readRememberedServerUrl();
  var keepLoginEnabled = readKeepLogin();
  var httpsEnabled = false;
  var isPlaying = true;
  var elapsed = 134;
  var duration = 248;
  var currentTrackIndex = 0;
  var clockTimer = null;
  var playbackTimer = null;
  var playerControlsTimer = null;
  var isRoaming = false;
  var savedQueueState = null;
  var cacheDialogOpen = false;
  var cacheDialogReturnFocus = null;
  var serverHistoryOpen = false;
  var serverHistoryReturnFocus = null;
  var cacheReleasePending = false;

  var tracks = Array.from(document.querySelectorAll('[data-action="play-track"]')).map(function (row) {
    return {
      title: row.dataset.title,
      artist: row.dataset.artist,
      duration: Number(row.dataset.duration),
      row: row
    };
  });

  function readPlayerMode() {
    try {
      return window.localStorage.getItem(PLAYER_MODE_KEY) === "cover" ? "cover" : "poster";
    } catch (error) {
      return "poster";
    }
  }

  function savePlayerMode(mode) {
    playerMode = mode === "poster" ? "poster" : "cover";
    try {
      window.localStorage.setItem(PLAYER_MODE_KEY, playerMode);
    } catch (error) {
      // The prototype remains usable when local file storage is unavailable.
    }
  }

  function readCacheLimit() {
    try {
      var storedLimit = Number(window.localStorage.getItem(CACHE_LIMIT_KEY));
      return CACHE_LIMITS.includes(storedLimit) ? storedLimit : 512;
    } catch (error) {
      return 512;
    }
  }

  function readCacheUsage(limit) {
    try {
      var storedUsage = window.localStorage.getItem(CACHE_USAGE_KEY);
      var parsedUsage = storedUsage === null ? 286 : Number(storedUsage);
      return Number.isFinite(parsedUsage) && parsedUsage >= 0 ? parsedUsage : 286;
    } catch (error) {
      return 286;
    }
  }

  function saveCacheSettings() {
    try {
      window.localStorage.setItem(CACHE_LIMIT_KEY, String(cacheLimit));
      window.localStorage.setItem(CACHE_USAGE_KEY, String(cacheUsage));
    } catch (error) {
      // Cache controls remain functional for the current session.
    }
  }

  function isValidServerUrl(value) {
    try {
      var parsed = new URL(value);
      return (parsed.protocol === "http:" || parsed.protocol === "https:")
        && Boolean(parsed.host)
        && !parsed.username
        && !parsed.password;
    } catch (error) {
      return false;
    }
  }

  function readRememberedServerUrl() {
    try {
      var storedUrl = window.localStorage.getItem(SERVER_URL_KEY) || "";
      return isValidServerUrl(storedUrl) ? storedUrl : "";
    } catch (error) {
      return "";
    }
  }

  function readKeepLogin() {
    try {
      return window.localStorage.getItem(KEEP_LOGIN_KEY) !== "false";
    } catch (error) {
      return true;
    }
  }

  function stripServerScheme(value) {
    return value.trim().replace(/^https?:\/\//i, "");
  }

  function normalizedServerUrl(value) {
    var candidate = (httpsEnabled ? "https://" : "http://") + stripServerScheme(value);
    try {
      var parsed = new URL(candidate);
      if (!parsed.pathname.endsWith("/")) {
        parsed.pathname += "/";
      }
      return parsed.toString();
    } catch (error) {
      return candidate;
    }
  }

  function isValidServerAddress(value) {
    var trimmed = value.trim();
    var scheme = trimmed.match(/^([a-z][a-z0-9+.-]*):\/\//i);
    if (scheme && !/^https?$/i.test(scheme[1])) {
      return false;
    }
    var authority = stripServerScheme(trimmed).split("/")[0];
    return Boolean(authority)
      && !/[\s@]/.test(authority)
      && isValidServerUrl(normalizedServerUrl(value));
  }

  function configureLoginForm() {
    var server = document.getElementById("server-input");
    var account = document.getElementById("account-input");
    if (rememberedServerUrl) {
      server.value = stripServerScheme(rememberedServerUrl);
      httpsEnabled = rememberedServerUrl.indexOf("https://") === 0;
      server.removeAttribute("data-autofocus");
      account.setAttribute("data-autofocus", "");
    } else {
      server.value = stripServerScheme(server.value);
      httpsEnabled = false;
      server.setAttribute("data-autofocus", "");
      account.removeAttribute("data-autofocus");
    }
    updateLoginOptions();
    updateServerSecurityNotice();
  }

  function updateServerSecurityNotice() {
    var server = document.getElementById("server-input");
    var notice = document.querySelector("[data-server-security-notice]");
    var isHttp = !httpsEnabled && isValidServerAddress(server.value);
    notice.hidden = !isHttp;
    notice.classList.toggle("is-visible", isHttp);
    notice.setAttribute("aria-hidden", String(!isHttp));
  }

  function handleServerInput() {
    var server = document.getElementById("server-input");
    var scheme = server.value.trim().match(/^(https?):\/\//i);
    if (scheme) {
      httpsEnabled = scheme[1].toLowerCase() === "https";
      server.value = stripServerScheme(server.value);
      updateLoginOptions();
      return;
    }
    updateServerSecurityNotice();
  }

  function updateLoginOptions() {
    var keepLogin = document.querySelector('[data-action="toggle-keep-login"]');
    var https = document.querySelector('[data-action="toggle-https"]');
    keepLogin.classList.toggle("is-checked", keepLoginEnabled);
    keepLogin.setAttribute("aria-checked", String(keepLoginEnabled));
    https.classList.toggle("is-checked", httpsEnabled);
    https.setAttribute("aria-checked", String(httpsEnabled));
    updateServerSecurityNotice();
  }

  function toggleKeepLogin() {
    keepLoginEnabled = !keepLoginEnabled;
    try {
      window.localStorage.setItem(KEEP_LOGIN_KEY, String(keepLoginEnabled));
    } catch (error) {
      // The review state still works when local storage is unavailable.
    }
    updateLoginOptions();
  }

  function toggleHttps() {
    httpsEnabled = !httpsEnabled;
    updateLoginOptions();
  }

  function updateRecentServerSelection() {
    var selectedUrl = normalizedServerUrl(document.getElementById("server-input").value);
    document.querySelectorAll("[data-recent-server]").forEach(function (option) {
      option.setAttribute("aria-selected", String(option.dataset.recentServer === selectedUrl));
    });
  }

  function openServerHistory(element) {
    var dialog = document.querySelector("[data-server-history-dialog]");
    serverHistoryOpen = true;
    serverHistoryReturnFocus = element;
    updateRecentServerSelection();
    dialog.hidden = false;
    var selected = dialog.querySelector('[data-recent-server][aria-selected="true"]');
    focusElement(selected || dialog.querySelector("[data-recent-server]"));
  }

  function closeServerHistory() {
    var returnFocus = serverHistoryReturnFocus || document.querySelector('[data-action="use-recent-server"]');
    document.querySelector("[data-server-history-dialog]").hidden = true;
    serverHistoryOpen = false;
    serverHistoryReturnFocus = null;
    focusElement(returnFocus);
  }

  function selectRecentServer(element) {
    var recent = element.dataset.recentServer;
    document.getElementById("server-input").value = stripServerScheme(recent);
    httpsEnabled = recent.indexOf("https://") === 0;
    updateLoginOptions();
    closeServerHistory();
  }

  function resetPasswordVisibility() {
    var password = document.getElementById("password-input");
    var toggle = document.querySelector('[data-action="toggle-password"]');
    password.type = "password";
    toggle.classList.remove("is-revealed");
    toggle.setAttribute("aria-pressed", "false");
    toggle.setAttribute("aria-label", "\u663E\u793A\u5BC6\u7801");
  }

  function togglePasswordVisibility() {
    var password = document.getElementById("password-input");
    var toggle = document.querySelector('[data-action="toggle-password"]');
    var reveal = password.type === "password";
    password.type = reveal ? "text" : "password";
    toggle.classList.toggle("is-revealed", reveal);
    toggle.setAttribute("aria-pressed", String(reveal));
    toggle.setAttribute("aria-label", reveal ? "\u9690\u85CF\u5BC6\u7801" : "\u663E\u793A\u5BC6\u7801");
  }

  function playerRoute() {
    return playerMode === "poster" ? "player-poster" : "player";
  }

  function routeFromHash() {
    var route = window.location.hash.replace(/^#/, "").split("?")[0];
    return VALID_ROUTES.has(route) ? route : "home";
  }

  function rawRouteFromHash() {
    return window.location.hash.replace(/^#/, "").split("?")[0];
  }

  function navigate(route) {
    if (!VALID_ROUTES.has(route)) {
      route = "home";
    }

    if (route === "playlist" && currentRoute !== "playlist") {
      routeBeforeDetail = currentRoute === "playlists" ? "playlists" : "home";
    }

    if ((route === "player" || route === "player-poster") && currentRoute !== "player" && currentRoute !== "player-poster") {
      routeBeforePlayer = currentRoute === "playlist" || currentRoute === "collection" ? currentRoute : currentRoute === "home" ? "home" : "my";
    }

    if (window.location.hash === "#" + route) {
      renderRoute(route);
      return;
    }
    window.location.hash = route;
  }

  function back() {
    if (currentRoute === "home") {
      showHomeExitState();
      return;
    }
    if (currentRoute === "login") {
      showLoginExitState();
      return;
    }
    if (currentRoute === "settings") {
      pendingFocusSelector = '.my-catalog-top [data-route="settings"]';
    }
    if (currentRoute === "my") {
      pendingFocusElement = lastHomeFocus;
    }
    var fallback = {
      settings: "my",
      playlists: "home",
      playlist: routeBeforeDetail,
      collection: "my",
      player: routeBeforePlayer,
      "player-poster": routeBeforePlayer,
      my: "home"
    };
    navigate(fallback[currentRoute] || "home");
  }

  function renderRoute(route) {
    currentRoute = route;
    var pageName = route === "player-poster" ? "player" : route;
    var exitState = document.querySelector("[data-home-exit-state]");
    var loginExitState = document.querySelector("[data-login-exit-state]");

    exitState.hidden = true;
    delete app.dataset.exitState;
    loginExitState.hidden = true;
    delete app.dataset.loginExitState;
    if (pageName !== "settings" && cacheDialogOpen) {
      document.querySelector("[data-cache-dialog]").hidden = true;
      cacheDialogOpen = false;
      cacheDialogReturnFocus = null;
    }
    if (pageName !== "login" && serverHistoryOpen) {
      document.querySelector("[data-server-history-dialog]").hidden = true;
      serverHistoryOpen = false;
      serverHistoryReturnFocus = null;
    }

    pages.forEach(function (page) {
      page.hidden = page.dataset.page !== pageName;
    });

    app.classList.toggle("player-open", pageName === "player");
    app.classList.toggle("login-open", pageName === "login");
    app.classList.toggle("settings-open", pageName === "settings");

    var playerPage = document.querySelector('[data-page="player"]');
    var posterOpen = route === "player-poster";
    playerPage.classList.toggle("is-poster", posterOpen);
    playerPage.classList.remove("controls-visible");
    if (pageName === "player") {
      showPlayerControls();
    }
    if (pageName === "login") {
      resetPasswordVisibility();
      updateServerSecurityNotice();
    }

    document.querySelectorAll("[data-route]").forEach(function (button) {
      var isPrimaryActive = button.dataset.route === route && (route === "home" || route === "my");
      button.classList.toggle("is-active", isPrimaryActive);
      if (button.classList.contains("nav-item")) {
        button.setAttribute("aria-current", isPrimaryActive ? "page" : "false");
      }
    });

    updatePlayerModeChoices();
    updateCacheSettings();
    updateRoamControls();
    updateProgress();
    updateLyrics();

    var rail = document.querySelector(".home-rail");
    rail.classList.remove("is-shifted");
    var myShelves = document.querySelector(".my-shelves");
    myShelves.dataset.activeShelf = "0";
    document.querySelectorAll(".shelf-track").forEach(function (track) {
      track.classList.remove("is-shifted");
    });

    window.requestAnimationFrame(function () {
      var activePage = document.querySelector('[data-page="' + pageName + '"]');
      var restoredElement = pendingFocusElement && activePage.contains(pendingFocusElement) ? pendingFocusElement : null;
      var restored = restoredElement || (pendingFocusSelector ? activePage.querySelector(pendingFocusSelector) : null);
      pendingFocusElement = null;
      pendingFocusSelector = "";
      var preferred = restored || activePage.querySelector("[data-autofocus]");
      var fallbackFocus = activePage.querySelector("[data-focusable]");
      focusElement(preferred || fallbackFocus || firstVisibleFocusable());
    });
  }

  function updatePlayerModeChoices() {
    document.querySelectorAll("[data-player-choice]").forEach(function (button) {
      button.setAttribute("aria-checked", String(button.dataset.playerChoice === playerMode));
    });
  }

  function updateRoamControls() {
    document.querySelector('[data-action="exit-roam"]').hidden = !isRoaming;
  }

  function setPlayerContext(value) {
    document.querySelector("[data-player-context]").textContent = value;
  }

  function startRoam() {
    if (isRoaming) {
      updateRoamControls();
      navigate(playerRoute());
      return;
    }
    savedQueueState = {
      trackIndex: currentTrackIndex,
      elapsed: elapsed,
      context: document.querySelector("[data-player-context]").textContent
    };
    isRoaming = true;
    setTrack(currentTrackIndex + 2, true);
    setPlayerContext("ROAM / \u968F\u673A\u6F2B\u6E38");
    updateRoamControls();
    navigate(playerRoute());
  }

  function leaveRoam() {
    if (!savedQueueState) {
      return;
    }
    var previousQueue = savedQueueState;
    isRoaming = false;
    savedQueueState = null;
    setTrack(previousQueue.trackIndex, false);
    elapsed = previousQueue.elapsed;
    isPlaying = false;
    setPlayerContext(previousQueue.context);
    updateRoamControls();
    updatePlaybackButton();
    updateProgress();
    updateLyrics();
    showPlayerControls(true);
  }

  function leaveRoamForQueue(context) {
    isRoaming = false;
    savedQueueState = null;
    setPlayerContext(context);
    updateRoamControls();
  }

  function updateCacheSettings() {
    document.querySelectorAll("[data-cache-choice]").forEach(function (button) {
      button.setAttribute("aria-checked", String(Number(button.dataset.cacheChoice) === cacheLimit));
    });
    document.querySelectorAll("[data-cache-usage]").forEach(function (element) {
      element.textContent = cacheUsage + " MB";
    });
    document.querySelectorAll("[data-cache-limit]").forEach(function (element) {
      element.textContent = cacheLimit + " MB";
    });
    var meter = document.querySelector(".cache-meter");
    var ratio = cacheLimit > 0 ? Math.min(1, cacheUsage / cacheLimit) : 0;
    meter.setAttribute("aria-valuemax", String(cacheLimit));
    meter.setAttribute("aria-valuenow", String(cacheUsage));
    meter.querySelector("[data-cache-progress]").style.width = ratio * 100 + "%";
    var releaseNote = document.querySelector("[data-cache-release-note]");
    releaseNote.hidden = !cacheReleasePending;
    document.querySelector("[data-active-cache-usage]").textContent = ACTIVE_CACHE_USAGE + " MB";
    document.querySelector("[data-cache-limit-note]").hidden = cacheUsage <= cacheLimit;
  }

  function openCacheDialog(element) {
    var dialog = document.querySelector("[data-cache-dialog]");
    var copy = document.querySelector("[data-cache-dialog-copy]");
    cacheDialogOpen = true;
    cacheDialogReturnFocus = element;
    copy.textContent = isPlaying
      ? "\u672A\u4F7F\u7528\u7684\u7F13\u5B58\u5C06\u7ACB\u5373\u5220\u9664\u3002\u5F53\u524D\u64AD\u653E\u5360\u7528\u4F1A\u5728\u6B4C\u66F2\u7ED3\u675F\u540E\u91CA\u653E\u3002"
      : "\u97F3\u9891\u548C\u56FE\u7247\u7F13\u5B58\u5C06\u7ACB\u5373\u5220\u9664\u3002\u8D44\u6599\u7D22\u5F15\u4E0D\u53D7\u5F71\u54CD\u3002";
    dialog.hidden = false;
    focusElement(dialog.querySelector('[data-action="cancel-clear-cache"]'));
  }

  function closeCacheDialog() {
    var returnFocus = cacheDialogReturnFocus || document.querySelector('[data-action="clear-cache"]');
    document.querySelector("[data-cache-dialog]").hidden = true;
    cacheDialogOpen = false;
    cacheDialogReturnFocus = null;
    focusElement(returnFocus);
  }

  function confirmClearCache() {
    cacheUsage = isPlaying ? Math.min(ACTIVE_CACHE_USAGE, cacheLimit) : 0;
    cacheReleasePending = cacheUsage > 0;
    saveCacheSettings();
    updateCacheSettings();
    closeCacheDialog();
  }

  function releaseActiveCache() {
    if (!cacheReleasePending) {
      return;
    }
    cacheUsage = 0;
    cacheReleasePending = false;
    saveCacheSettings();
    updateCacheSettings();
  }

  function applyCacheSafePoint() {
    releaseActiveCache();
    if (cacheUsage <= cacheLimit) {
      return;
    }
    cacheUsage = cacheLimit;
    saveCacheSettings();
    updateCacheSettings();
  }

  function showHomeExitState() {
    var exitState = document.querySelector("[data-home-exit-state]");
    if (!exitState.hidden) {
      app.dataset.exitState = "confirmed";
      return;
    }
    exitState.hidden = false;
    app.dataset.exitState = "requested";
    document.querySelectorAll(".is-focused").forEach(function (focused) {
      focused.classList.remove("is-focused");
    });
    if (currentFocus) {
      currentFocus.blur();
    }
    currentFocus = null;
  }

  function showLoginExitState() {
    var exitState = document.querySelector("[data-login-exit-state]");
    if (!exitState.hidden) {
      app.dataset.loginExitState = "confirmed";
      return;
    }
    exitState.hidden = false;
    app.dataset.loginExitState = "requested";
    document.querySelectorAll(".is-focused").forEach(function (focused) {
      focused.classList.remove("is-focused");
    });
    if (currentFocus) {
      currentFocus.blur();
    }
    currentFocus = null;
  }

  function hidePlayerControls() {
    var playerPage = document.querySelector('[data-page="player"]');
    window.clearTimeout(playerControlsTimer);
    playerControlsTimer = null;
    playerPage.classList.remove("controls-visible");

    if (currentFocus && (currentFocus.closest(".player-controls") || currentFocus.classList.contains("player-back"))) {
      currentFocus.classList.remove("is-focused");
      currentFocus.blur();
      currentFocus = null;
    }
  }

  function showPlayerControls(focusPlayButton) {
    if (currentRoute !== "player" && currentRoute !== "player-poster") {
      return;
    }
    var playerPage = document.querySelector('[data-page="player"]');
    playerPage.classList.add("controls-visible");
    window.clearTimeout(playerControlsTimer);
    playerControlsTimer = window.setTimeout(function () {
      hidePlayerControls();
    }, 5000);
    if (focusPlayButton === true) {
      focusElement(playerPage.querySelector(".control-play"));
    }
  }

  function visibleFocusables() {
    var scope = cacheDialogOpen
      ? document.querySelector("[data-cache-dialog]")
      : serverHistoryOpen
        ? document.querySelector("[data-server-history-dialog]")
        : document;
    return Array.from(scope.querySelectorAll("[data-focusable]")).filter(function (element) {
      var style = window.getComputedStyle(element);
      return !element.disabled
        && element.getClientRects().length > 0
        && style.display !== "none"
        && style.visibility !== "hidden";
    });
  }

  function firstVisibleFocusable() {
    return visibleFocusables()[0] || null;
  }

  function focusElement(element) {
    if (!element || element === currentFocus) {
      return;
    }

    document.querySelectorAll(".is-focused").forEach(function (focused) {
      focused.classList.remove("is-focused");
    });

    currentFocus = element;
    currentFocus.classList.add("is-focused");
    currentFocus.focus({ preventScroll: true });
    revealRailCard(currentFocus);
    revealCatalogItem(currentFocus);
    if (currentRoute === "home" && currentFocus.closest('[data-page="home"]')) {
      lastHomeFocus = currentFocus;
    }
  }

  function handleFocusIn(event) {
    if (event.target instanceof HTMLElement && event.target.hasAttribute("data-focusable")) {
      focusElement(event.target);
    }
  }

  function revealRailCard(element) {
    var rail = document.querySelector(".home-rail");
    if (!element.classList.contains("playlist-card") || element.parentElement !== rail) {
      return;
    }
    var index = Array.from(rail.children).indexOf(element);
    rail.classList.toggle("is-shifted", index >= 4);
  }

  function revealCatalogItem(element) {
    var shelf = element.closest(".my-shelf");
    var track = element.closest(".shelf-track");
    if (!shelf || !track) {
      return;
    }
    document.querySelector(".my-shelves").dataset.activeShelf = shelf.dataset.shelfIndex;
    var index = Array.from(track.children).indexOf(element);
    var shiftAt = track.classList.contains("artist-track") ? 4 : track.classList.contains("album-track") ? 5 : 3;
    track.classList.toggle("is-shifted", index >= shiftAt);
  }

  function updateCollectionSurface(element) {
    document.querySelector("[data-collection-name-display]").textContent = element.dataset.collectionName;
    document.querySelector("[data-collection-context-display]").textContent = element.dataset.collectionContext;
    document.querySelector("[data-collection-meta-display]").textContent = element.dataset.collectionMeta;
  }

  function collectionQueueContext() {
    var context = document.querySelector("[data-collection-context-display]").textContent;
    var name = document.querySelector("[data-collection-name-display]").textContent;
    return context + " / " + name;
  }

  function elementCenter(element) {
    var rect = element.getBoundingClientRect();
    return {
      x: rect.left + rect.width / 2,
      y: rect.top + rect.height / 2
    };
  }

  function moveFocus(direction) {
    var candidates = visibleFocusables();
    if (!currentFocus || !candidates.includes(currentFocus)) {
      focusElement(candidates[0]);
      return;
    }

    var origin = elementCenter(currentFocus);
    var best = null;
    var bestScore = Infinity;

    candidates.forEach(function (candidate) {
      if (candidate === currentFocus) {
        return;
      }
      var point = elementCenter(candidate);
      var dx = point.x - origin.x;
      var dy = point.y - origin.y;
      var primary;
      var secondary;

      if (direction === "left" && dx < -8) {
        primary = -dx;
        secondary = Math.abs(dy);
      } else if (direction === "right" && dx > 8) {
        primary = dx;
        secondary = Math.abs(dy);
      } else if (direction === "up" && dy < -8) {
        primary = -dy;
        secondary = Math.abs(dx);
      } else if (direction === "down" && dy > 8) {
        primary = dy;
        secondary = Math.abs(dx);
      } else {
        return;
      }

      var score = primary + secondary * 3.4 + (secondary > primary * 1.6 ? 900 : 0);
      if (score < bestScore) {
        bestScore = score;
        best = candidate;
      }
    });

    if (best) {
      focusElement(best);
    }
  }

  function setTrack(index, resetElapsed) {
    if (!tracks.length) {
      return;
    }
    applyCacheSafePoint();
    currentTrackIndex = (index + tracks.length) % tracks.length;
    var track = tracks[currentTrackIndex];
    duration = track.duration;
    if (resetElapsed !== false) {
      elapsed = 0;
    }
    isPlaying = true;

    document.querySelectorAll("[data-current-title]").forEach(function (element) {
      element.textContent = track.title;
    });
    document.querySelectorAll("[data-current-artist]").forEach(function (element) {
      element.textContent = track.artist;
    });

    tracks.forEach(function (item, itemIndex) {
      var active = itemIndex === currentTrackIndex;
      var indexElement = item.row.querySelector(".track-index");
      item.row.classList.toggle("is-current", active);
      if (active) {
        indexElement.innerHTML = "<i></i><i></i><i></i>";
      } else {
        indexElement.textContent = String(itemIndex + 1).padStart(2, "0");
      }
    });

    updatePlaybackButton();
    updateProgress();
    updateLyrics();
  }

  function formatTime(seconds) {
    var safeSeconds = Math.max(0, Math.floor(seconds));
    var minutes = Math.floor(safeSeconds / 60);
    var remainder = String(safeSeconds % 60).padStart(2, "0");
    return String(minutes).padStart(2, "0") + ":" + remainder;
  }

  function updateProgress() {
    var ratio = duration > 0 ? Math.min(1, elapsed / duration) : 0;
    document.querySelectorAll("[data-elapsed]").forEach(function (element) {
      element.textContent = formatTime(elapsed);
    });
    document.querySelectorAll("[data-total]").forEach(function (element) {
      element.textContent = formatTime(duration);
    });
    document.querySelectorAll("[data-progress]").forEach(function (element) {
      element.style.width = ratio * 100 + "%";
    });
    document.querySelectorAll("[data-progress-knob]").forEach(function (element) {
      element.style.left = ratio * 100 + "%";
    });
    document.querySelectorAll('[role="slider"][data-focusable]').forEach(function (slider) {
      slider.setAttribute("aria-valuemax", String(duration));
      slider.setAttribute("aria-valuenow", String(Math.floor(elapsed)));
      slider.setAttribute("aria-valuetext", formatTime(elapsed) + " / " + formatTime(duration));
    });
  }

  function updateLyrics() {
    var lines = Array.from(document.querySelectorAll("[data-lyric-at]"));
    var activeIndex = 0;
    lines.forEach(function (line, index) {
      if (elapsed >= Number(line.dataset.lyricAt)) {
        activeIndex = index;
      }
    });
    lines.forEach(function (line, index) {
      line.classList.toggle("is-active", index === activeIndex);
    });
    document.querySelector(".lyrics").style.setProperty("--lyric-offset", activeIndex * 214 + "px");
  }

  function updatePlaybackButton() {
    document.querySelectorAll('[data-action="toggle-play"]').forEach(function (button) {
      button.setAttribute("aria-label", isPlaying ? "Pause" : "Play");
      button.querySelector("[data-play-icon]").innerHTML = isPlaying ? "&#10074;&#10074;" : "&#9654;";
    });
  }

  function togglePlayback() {
    isPlaying = !isPlaying;
    updatePlaybackButton();
  }

  function seekBy(seconds) {
    elapsed = Math.max(0, Math.min(duration, elapsed + seconds));
    updateProgress();
    updateLyrics();
  }

  function handleAction(action, element) {
    switch (action) {
      case "back":
        back();
        break;
      case "open-default-player":
        navigate(playerRoute());
        break;
      case "start-roam":
        startRoam();
        break;
      case "play-all":
        leaveRoamForQueue(currentRoute === "collection" ? collectionQueueContext() : "PLAYLIST / \u6DF1\u591C\u516C\u8DEF");
        setTrack(0, true);
        navigate(playerRoute());
        break;
      case "play-track":
        leaveRoamForQueue("PLAYLIST / \u6DF1\u591C\u516C\u8DEF");
        setTrack(tracks.findIndex(function (track) { return track.row === element; }), true);
        navigate(playerRoute());
        break;
      case "play-collection-track":
        leaveRoamForQueue(collectionQueueContext());
        setTrack(Number(element.dataset.trackIndex), true);
        navigate(playerRoute());
        break;
      case "exit-roam":
        leaveRoam();
        break;
      case "toggle-play":
        togglePlayback();
        break;
      case "previous":
        setTrack(currentTrackIndex - 1, true);
        break;
      case "next":
        setTrack(currentTrackIndex + 1, true);
        break;
      case "clear-cache":
        openCacheDialog(element);
        break;
      case "cancel-clear-cache":
        closeCacheDialog();
        break;
      case "confirm-clear-cache":
        confirmClearCache();
        break;
      case "toggle-password":
        togglePasswordVisibility();
        break;
      case "use-recent-server":
        openServerHistory(element);
        break;
      case "select-recent-server":
        selectRecentServer(element);
        break;
      case "cancel-server-history":
        closeServerHistory();
        break;
      case "toggle-keep-login":
        toggleKeepLogin();
        break;
      case "toggle-https":
        toggleHttps();
        break;
      default:
        break;
    }
  }

  function handleClick(event) {
    if (cacheDialogOpen && !event.target.closest(".cache-dialog-panel")) {
      return;
    }
    if (serverHistoryOpen && !event.target.closest(".server-history-dialog-panel")) {
      return;
    }
    showPlayerControls();
    var focusTarget = event.target.closest("[data-focusable]");
    if (focusTarget) {
      focusElement(focusTarget);
    }

    var routeTarget = event.target.closest("[data-route]");
    if (routeTarget) {
      if (routeTarget.dataset.route === "collection") {
        updateCollectionSurface(routeTarget);
      }
      navigate(routeTarget.dataset.route);
      return;
    }

    var playerChoice = event.target.closest("[data-player-choice]");
    if (playerChoice) {
      savePlayerMode(playerChoice.dataset.playerChoice);
      updatePlayerModeChoices();
      return;
    }

    var cacheChoice = event.target.closest("[data-cache-choice]");
    if (cacheChoice) {
      var requestedLimit = Number(cacheChoice.dataset.cacheChoice);
      if (CACHE_LIMITS.includes(requestedLimit)) {
        cacheLimit = requestedLimit;
        saveCacheSettings();
        updateCacheSettings();
      }
      return;
    }

    var actionTarget = event.target.closest("[data-action]");
    if (actionTarget) {
      handleAction(actionTarget.dataset.action, actionTarget);
    }
  }

  function moveLoginFocus(direction) {
    var server = document.getElementById("server-input");
    var history = document.querySelector('[data-action="use-recent-server"]');
    var account = document.getElementById("account-input");
    var password = document.getElementById("password-input");
    var eye = document.querySelector('[data-action="toggle-password"]');
    var keepLogin = document.querySelector('[data-action="toggle-keep-login"]');
    var https = document.querySelector('[data-action="toggle-https"]');
    var submit = document.querySelector(".login-submit");
    var loginFocusables = [server, history, account, password, eye, keepLogin, https, submit];
    if (!loginFocusables.includes(currentFocus)) {
      return false;
    }

    if (direction === "right") {
      focusElement(currentFocus === server ? history : currentFocus === password ? eye : currentFocus);
      return true;
    }
    if (direction === "left") {
      focusElement(currentFocus === history ? server : currentFocus === eye ? password : currentFocus);
      return true;
    }

    var verticalOrder = [server, account, password, keepLogin, https, submit];
    var verticalFocus = currentFocus === history ? server : currentFocus === eye ? password : currentFocus;
    var index = verticalOrder.indexOf(verticalFocus);
    var nextIndex = Math.max(0, Math.min(verticalOrder.length - 1, index + (direction === "down" ? 1 : -1)));
    focusElement(verticalOrder[nextIndex]);
    return true;
  }

  function handleCacheDialogKeyDown(event) {
    if (!cacheDialogOpen) {
      return false;
    }
    var buttons = Array.from(document.querySelectorAll("[data-cache-dialog] [data-focusable]"));
    if (event.key === "Escape" || event.key === "Backspace") {
      event.preventDefault();
      closeCacheDialog();
      return true;
    }
    if (event.key === "Enter") {
      event.preventDefault();
      (buttons.includes(currentFocus) ? currentFocus : buttons[0]).click();
      return true;
    }
    if (["ArrowLeft", "ArrowRight", "ArrowUp", "ArrowDown", "Tab"].includes(event.key)) {
      event.preventDefault();
      var index = Math.max(0, buttons.indexOf(currentFocus));
      var backwards = event.key === "ArrowLeft" || event.key === "ArrowUp" || (event.key === "Tab" && event.shiftKey);
      focusElement(buttons[(index + (backwards ? -1 : 1) + buttons.length) % buttons.length]);
      return true;
    }
    event.preventDefault();
    return true;
  }

  function handleServerHistoryKeyDown(event) {
    if (!serverHistoryOpen) {
      return false;
    }
    var buttons = Array.from(document.querySelectorAll("[data-server-history-dialog] [data-focusable]"));
    if (event.key === "Escape" || event.key === "Backspace") {
      event.preventDefault();
      closeServerHistory();
      return true;
    }
    if (event.key === "Enter") {
      event.preventDefault();
      (buttons.includes(currentFocus) ? currentFocus : buttons[0]).click();
      return true;
    }
    if (["ArrowLeft", "ArrowRight", "ArrowUp", "ArrowDown", "Tab"].includes(event.key)) {
      event.preventDefault();
      var index = Math.max(0, buttons.indexOf(currentFocus));
      var backwards = event.key === "ArrowLeft" || event.key === "ArrowUp" || (event.key === "Tab" && event.shiftKey);
      focusElement(buttons[(index + (backwards ? -1 : 1) + buttons.length) % buttons.length]);
      return true;
    }
    event.preventDefault();
    return true;
  }

  function handleKeyDown(event) {
    var directionByKey = {
      ArrowLeft: "left",
      ArrowRight: "right",
      ArrowUp: "up",
      ArrowDown: "down"
    };
    var playerPage = document.querySelector('[data-page="player"]');
    var playerOpen = currentRoute === "player" || currentRoute === "player-poster";
    var controlsVisible = playerPage.classList.contains("controls-visible");

    if (handleServerHistoryKeyDown(event) || handleCacheDialogKeyDown(event)) {
      return;
    }

    var loginExitState = document.querySelector("[data-login-exit-state]");
    if (!loginExitState.hidden) {
      if (event.key === "Escape" || event.key === "Backspace") {
        event.preventDefault();
        showLoginExitState();
      }
      return;
    }

    var exitState = document.querySelector("[data-home-exit-state]");
    if (!exitState.hidden) {
      if (event.key === "Escape" || event.key === "Backspace") {
        event.preventDefault();
        showHomeExitState();
      }
      return;
    }

    if (event.key === "Enter" && playerOpen && !controlsVisible) {
      event.preventDefault();
      togglePlayback();
      showPlayerControls(true);
      return;
    }

    if (directionByKey[event.key]) {
      event.preventDefault();
      if (playerOpen && !controlsVisible) {
        showPlayerControls(true);
        return;
      }
      if (currentRoute === "login" && moveLoginFocus(directionByKey[event.key])) {
        return;
      }
      if (playerOpen && currentFocus && currentFocus.classList.contains("progress-track") && (event.key === "ArrowLeft" || event.key === "ArrowRight")) {
        seekBy(event.key === "ArrowLeft" ? -10 : 10);
        showPlayerControls();
        return;
      }
      showPlayerControls();
      moveFocus(directionByKey[event.key]);
      return;
    }

    if (event.key === "Escape" || event.key === "Backspace") {
      if (event.target instanceof HTMLInputElement && event.key === "Backspace") {
        return;
      }
      event.preventDefault();
      if (playerOpen && controlsVisible) {
        hidePlayerControls();
        return;
      }
      back();
      return;
    }

    if (event.key === "Enter") {
      showPlayerControls();
      if (event.target === document.getElementById("server-input")) {
        event.preventDefault();
        focusElement(document.getElementById("account-input"));
        return;
      }
      if (event.target === document.getElementById("account-input")) {
        event.preventDefault();
        focusElement(document.getElementById("password-input"));
        return;
      }
      if (event.target === document.getElementById("password-input")) {
        event.preventDefault();
        focusElement(document.querySelector('[data-action="toggle-keep-login"]'));
        return;
      }
      if (playerOpen && currentFocus && currentFocus.classList.contains("progress-track")) {
        event.preventDefault();
        togglePlayback();
        showPlayerControls();
        return;
      }
      var enterTarget = event.target instanceof HTMLButtonElement && event.target.hasAttribute("data-focusable")
        ? event.target
        : currentFocus instanceof HTMLButtonElement && currentFocus.getClientRects().length > 0
          ? currentFocus
          : null;
      if (enterTarget) {
        event.preventDefault();
        enterTarget.click();
      }
    }
  }

  function handleLogin(event) {
    event.preventDefault();
    var server = document.getElementById("server-input");
    var account = document.getElementById("account-input");
    var password = document.getElementById("password-input");
    var feedback = document.getElementById("form-feedback");
    var serverUrl = normalizedServerUrl(server.value);
    var validServer = isValidServerAddress(server.value) && isValidServerUrl(serverUrl);
    var valid = validServer && account.value.trim().length > 0 && password.value.length > 0;
    feedback.classList.toggle("is-visible", !valid);
    if (valid) {
      rememberedServerUrl = serverUrl;
      password.value = "";
      resetPasswordVisibility();
      try {
        window.localStorage.setItem(SERVER_URL_KEY, rememberedServerUrl);
      } catch (error) {
        // Login still succeeds when local file storage is unavailable.
      }
      configureLoginForm();
      navigate("home");
    } else {
      password.value = "";
      resetPasswordVisibility();
      focusElement(!validServer ? server : account.value.trim() ? password : account);
    }
  }

  function resizeCanvas() {
    var scale = Math.min(window.innerWidth / CANVAS_WIDTH, window.innerHeight / CANVAS_HEIGHT);
    var left = (window.innerWidth - CANVAS_WIDTH * scale) / 2;
    var top = (window.innerHeight - CANVAS_HEIGHT * scale) / 2;
    root.style.left = left + "px";
    root.style.top = top + "px";
    root.style.transform = "scale(" + scale + ")";
  }

  function updateClock() {
    var now = new Date();
    document.getElementById("clock").textContent = String(now.getHours()).padStart(2, "0") + ":" + String(now.getMinutes()).padStart(2, "0");
  }

  function tickPlayback() {
    if (!isPlaying) {
      return;
    }
    elapsed += 1;
    if (elapsed >= duration) {
      setTrack(currentTrackIndex + 1, true);
      return;
    }
    updateProgress();
    updateLyrics();
  }

  function initialize() {
    resizeCanvas();
    updateClock();
    updatePlaybackButton();
    setTrack(0, false);
    saveCacheSettings();
    configureLoginForm();

    document.addEventListener("click", handleClick);
    document.addEventListener("focusin", handleFocusIn);
    document.addEventListener("keydown", handleKeyDown);
    document.addEventListener("mousemove", showPlayerControls);
    document.getElementById("login-form").addEventListener("submit", handleLogin);
    document.getElementById("server-input").addEventListener("input", handleServerInput);
    window.addEventListener("resize", resizeCanvas);
    window.addEventListener("hashchange", function () {
      renderRoute(routeFromHash());
    });

    clockTimer = window.setInterval(updateClock, 30000);
    playbackTimer = window.setInterval(tickPlayback, 1000);

    if (!window.location.hash || !VALID_ROUTES.has(rawRouteFromHash())) {
      window.location.replace("#home");
    }
    renderRoute(routeFromHash());
  }

  initialize();

  window.addEventListener("beforeunload", function () {
    window.clearInterval(clockTimer);
    window.clearInterval(playbackTimer);
    window.clearTimeout(playerControlsTimer);
  });
})();
