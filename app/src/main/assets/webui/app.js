"use strict";
const $ = (id) => document.getElementById(id);
const audio = $("audio");
const state = {
  view: "home",
  history: [],
  library: [],
  queue: [],
  original: [],
  index: -1,
  current: null,
  collection: null,
  playing: false,
  shuffle: false,
  smart: false,
  manual: new Set(),
  smartKeys: new Set(),
  statsDays: 30,
  repeat: true,
  generation: 0,
  renderGeneration: 0,
  nextReady: null,
  preloadTimer: null,
  recoveryTimer: null,
  ticket: "",
  audible: 0,
  lastTick: 0,
  qualified: false,
  started: false,
  retries: 0,
  refilling: false,
};
const nodes = new Map();
const el = (tag, cls, text) => {
  const n = document.createElement(tag);
  if (cls) n.className = cls;
  if (text !== undefined) n.textContent = text;
  return n;
};
const time = (value) =>
  Number.isFinite(value) && value >= 0
    ? `${Math.floor(value / 60)}:${String(Math.floor(value % 60)).padStart(2, "0")}`
    : "0:00";
const viewPositions = new Map();
function savePosition() {
  viewPositions.set(state.view, document.querySelector("main")?.scrollTop || 0);
}
let pageMotion;
function revealPage() {
  pageMotion?.cancel();
  const content = $("content");
  if (!content?.animate || window.matchMedia("(prefers-reduced-motion: reduce)").matches) return;
  pageMotion = content.animate([
    { opacity: .45, transform: "translateX(10px)" },
    { opacity: 1, transform: "translateX(0)" }
  ], { duration: 200, easing: "cubic-bezier(.2,.8,.2,1)" });
}
function restorePosition() {
  revealPage();
  requestAnimationFrame(() => {
    const main = document.querySelector("main");
    if (main)
      main.scrollTop =
        state.view === "home" ? 0 : viewPositions.get(state.view) || 0;
  });
}
let toastTimer;
let downloadRefreshTimer,
  downloadRequest = 0;
function toast(message) {
  $("toast").textContent = message;
  $("toast").hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => ($("toast").hidden = true), 5000);
}
async function api(action, extra = {}) {
  const res = await fetch(
    action === "ping" ? "/ping" : "/api",
    action === "ping"
      ? { signal: AbortSignal.timeout(6000) }
      : {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ action, ...extra }),
          signal: AbortSignal.timeout(65000),
        },
  );
  const data = await res.json();
  if (!res.ok) {
    if (res.status === 401) {
      audio.pause();
      $("pair").hidden = false;
      $("shell").hidden = true;
    }
    throw new Error(data.error || "Connection unavailable. Check your phone.");
  }
  return data;
}
function image(src, cls = "") {
  const img = el("img", cls);
  img.alt = "";
  img.loading = "lazy";
  img.referrerPolicy = "no-referrer";
  if (src) img.src = src;
  else img.hidden = true;
  img.addEventListener("error", () => (img.hidden = true));
  return img;
}
function bars() {
  const b = el("span", "bars");
  for (let i = 0; i < 3; i++) b.append(el("i"));
  return b;
}
function keep(item) {
  nodes.set(item.key, item);
  return item;
}
function card(item) {
  keep(item);
  const b = el("button", "music-card");
  b.dataset.key = item.key;
  const art = el("div", "art art-frame");
  art.append(el("span", "art-fallback", item.title === "Downloaded" ? "↓" : item.title === "Liked Songs" ? "♡" : "♫"));
  if (item.image) art.append(image(item.image));
  b.append(
    art,
    el("strong", "", item.title),
    el("small", "", item.subtitle || item.type),
  );
  b.onclick = () => openItem(item);
  b.oncontextmenu = (e) => {
    e.preventDefault();
    item.type === "song" ? songMenu(item) : collectionMenu(item);
  };
  if (
    state.current?.key === item.key ||
    (state.collection?.key === item.key && state.playing)
  )
    b.classList.add("active");
  const wrapper = el("div", "card-wrap");
  const menu = button(
    "•••",
    () => (item.type === "song" ? songMenu(item) : collectionMenu(item)),
    "card-menu",
  );
  menu.ariaLabel = `Options for ${item.title}`;
  wrapper.append(b, menu);
  return wrapper;
}
function section(title, items, parent = $("content")) {
  if (!items?.length) return;
  const shelf = el("section", "section"),
    head = el("div", "section-heading"),
    grid = el("div", "cards");
  let expanded = false;
  head.append(el("h2", "", title));
  const more = button(
    "See all",
    () => {
      expanded = !expanded;
      draw();
    },
    "quiet",
  );
  if (items.length > 6) head.append(more);
  function draw() {
    grid.replaceChildren();
    (expanded ? items : items.slice(0, 6)).forEach((item) =>
      grid.append(card(item)),
    );
    more.textContent = expanded ? "Show less" : "See all";
    highlight();
  }
  shelf.append(head, grid);
  parent.append(shelf);
  draw();
}

function rows(songs, parent = $("content")) {
  if (!songs.length) {
    parent.append(
      el(
        "p",
        "empty",
        "Nothing here yet. Try a search or check the account on your phone.",
      ),
    );
    return;
  }
  const wrap = el("div", "tracks");
  const more = el("button", "chip", "Show more songs");
  let visible = 0;
  parent.append(wrap);
  function appendBatch() {
    const end = Math.min(songs.length, visible + 100);
    for (let index = visible; index < end; index++) {
      const song = songs[index];
      keep(song);
      const r = el("div", "track-row");
      r.dataset.key = song.key;
      r.append(el("span", "", String(index + 1)), image(song.image));
      const b = el("button", "track-name");
      b.append(el("strong", "", song.title), el("small", "", song.subtitle));
      const collection = state.viewCollection;
      b.onclick = () => playList(songs, index, collection);
      const radio = button("◉", () => createRadio(song));
      radio.ariaLabel = `Start radio from ${song.title}`;
      radio.title = "Start USpot Radio";
      const queue = button("+", () => enqueue(song));
      queue.ariaLabel = `Add ${song.title} to queue`;
      queue.title = "Add to queue";
      const menu = button("•••", () => songMenu(song));
      menu.ariaLabel = `Options for ${song.title}`;
      r.oncontextmenu = (e) => {
        e.preventDefault();
        songMenu(song);
      };
      const like = button(song.liked ? "♥" : "♡", async () => {
        await toggleLike(song);
        like.textContent = song.liked ? "♥" : "♡";
        like.classList.toggle("active", !!song.liked);
      });
      like.ariaLabel = `${song.liked ? "Unlike" : "Like"} ${song.title}`;
      like.dataset.likeKey = song.key;
      like.classList.toggle("active", !!song.liked);
      r.append(b, like, radio, queue, menu);
      wrap.append(r);
    }
    visible = end;
    more.remove();
    if (visible < songs.length) parent.append(more);
    highlight();
  }
  more.onclick = appendBatch;
  appendBatch();
}
function loading() {
  const n = el("div", "loading");
  n.append(el("i", "spinner"), el("p", "", "Finding your music…"));
  for (let i = 0; i < 4; i++) {
    const row = el("div", "skeleton-row");
    row.append(el("span", "skeleton-art"), el("span", "skeleton-copy"));
    n.append(row);
  }
  $("content").replaceChildren(n);
}
function errorPage(message) {
  const n = el("div", "empty");
  n.append(el("h2", "", "Let’s reconnect."), el("p", "", message));
  const retry = el("button", "primary", "Try again");
  retry.onclick = () => navigate(state.view, false);
  n.append(el("br"), retry);
  $("content").replaceChildren(n);
}
async function navigate(view, push = true) {
  savePosition();
  if (view.startsWith("collection:")) {
    const item = nodes.get(view.slice(11));
    if (item) {
      await openItem(item, false);
      return;
    }
    view = "home";
  }
  if (push && state.view !== view) state.history.push(state.view);
  state.view = view;
  state.viewCollection = null;
  const generation = ++state.renderGeneration;
  document
    .querySelectorAll("[data-view]")
    .forEach((n) => n.classList.toggle("active", n.dataset.view === view));
  loading();
  try {
    let data;
    if (view === "home") {
      data = await api("home");
      if (generation !== state.renderGeneration) return;
      $("content").replaceChildren();
      if (true) {
        const s = el("section", "section");
        const head = el("div", "section-heading");
        head.append(el("h2", "", "Your rotation"));
        const plus = el("button", "circle", "+");
        plus.title = "Create USpot Radio or Radio Mix";
        plus.onclick = radioChoice;
        head.append(plus);
        const grid = el("div", "shortcut-grid");
        (data.shortcuts || []).forEach((i) => {
          keep(i);
          const b = el("button", "shortcut");
          b.dataset.key = i.key;
          b.append(image(i.image), el("strong", "", i.title));
          if (state.collection?.key === i.key && state.playing)
            b.append(bars());
          b.onclick = () => openItem(i);
          b.oncontextmenu = (e) => {
            e.preventDefault();
            collectionMenu(i);
          };
          grid.append(b);
        });
        s.append(head, grid);
        $("content").append(s);
      }
      data.sections.forEach((s) => section(s.title, s.items));
    } else if (view === "search") {
      if ($("search").value.trim()) {
        await search(state.searchType || "All");
        restorePosition();
        return;
      }
      $("content").replaceChildren(
        el("span", "eyebrow", "A SONG, AN ARTIST, A WHOLE NEW MOOD"),
        el("h1", "", "Find something you love."),
        el("p", "empty", "Search Spotify’s catalog using the bar above."),
      );
      renderRecentSearches();
      $("search").focus();
    } else if (view === "following") {
      data = await api("following");
      if (generation !== state.renderGeneration) return;
      $("content").replaceChildren(
        el("h1", "", "From the artists you follow."),
      );
      section("Fresh releases", data.releases);
      section("Your artists", data.artists);
      const group = el("section", "section");
      const head = el("div", "section-heading");
      head.append(el("h2", "", "Popular with your favorites"));
      const b = el("button", "primary", "Play mix");
      b.onclick = () =>
        playList(data.songs, 0, { key: "following", title: "Following mix" });
      head.append(b);
      group.append(head);
      rows(data.songs, group);
      $("content").append(group);
    } else if (view === "stats") {
      data = await api("stats", { days: state.statsDays });
      if (generation !== state.renderGeneration) return;
      $("content").replaceChildren(
        el(
          "span",
          "eyebrow",
          state.statsDays === 0
            ? "ALL TIME"
            : `YOUR LAST ${state.statsDays} DAYS`,
        ),
        el("h1", "", "This is your soundtrack."),
      );
      const ranges = el("div", "filter-row");
      [
        [7, "Week"],
        [30, "Month"],
        [0, "All time"],
      ].forEach(([days, label]) =>
        ranges.append(
          button(
            label,
            () => {
              state.statsDays = days;
              navigate("stats", false);
            },
            "chip " + (days === state.statsDays ? "active" : ""),
          ),
        ),
      );
      $("content").append(ranges);
      const grid = el("div", "stat-grid");
      [
        [data.plays, "Qualified plays"],
        [data.artists, "Artists"],
        [data.minutes, "Minutes recorded"],
      ].forEach(([v, t]) => {
        const n = el("div", "stat");
        n.append(el("strong", "", String(v)), el("small", "", t));
        grid.append(n);
      });
      $("content").append(grid, el("h2", "", "Your week in music"));
      const chart = el("div", "activity");
      const max = Math.max(1, ...data.activity.map((d) => d.plays));
      data.activity.forEach((d) => {
        const n = el("div");
        n.style.height = `${Math.max(4, (d.plays / max) * 100)}%`;
        n.title = `${d.day}: ${d.plays} plays`;
        chart.append(n);
      });
      $("content").append(chart, el("h2", "", "Your top songs"));
      rows(data.songs);
      if (data.topArtists?.length) {
        const artists = el("section", "section");
        artists.append(el("h2", "", "Your top artists"));
        data.topArtists.forEach((a) => {
          const row = el("div", "setting-row");
          row.append(
            image(a.image),
            el("strong", "", a.name),
            el("small", "", `${a.plays} plays`),
          );
          artists.append(row);
        });
        $("content").append(artists);
      }
    } else if (view === "downloads") {
      await renderDownloadManager(generation);
    } else if (view === "settings") {
      await renderSettings(generation);
    } else if (view === "local" || view === "history") {
      data = await api(view);
      if (generation !== state.renderGeneration) return;
      $("content").replaceChildren(
        el("h1", "", view === "local" ? "Local files" : "Listening history"),
        el(
          "p",
          "muted",
          view === "local"
            ? "Songs imported on your phone. Add files or folders in the Android app."
            : "Qualified listening from this app. Your data stays local.",
        ),
      );
      if (view === "history")
        $("content").append(
          button(
            "Clear history",
            () =>
              confirmAction(
                "Clear listening history?",
                "This removes local listening history and statistics. It does not delete Spotify music.",
                async () => {
                  await api("clearHistory");
                  navigate("history", false);
                },
              ),
            "chip",
          ),
        );
      rows(data.songs);
    } else {
      await loadLibrary();
      if (generation !== state.renderGeneration) return;
      $("content").replaceChildren(
        el(
          "span",
          "eyebrow",
          view === "radios"
            ? "KEEP THE DISCOVERY GOING"
            : "YOUR MUSIC",
        ),
        el(
          "h1",
          "",
          view === "radios" ? "Your radio, your way." : "Your Library",
        ),
      );
      const items = state.library.filter(
        (i) => view !== "radios" || i.type === "radio",
      );
      renderLibraryItems(items, view);
      if (!items.length)
        $("content").append(
          el("p", "empty", "Create a radio from a song using the ◉ button."),
        );
    }
    highlight();
    restorePosition();
  } catch (error) {
    if (generation === state.renderGeneration) errorPage(error.message);
  }
}
async function loadLibrary() {
  const data = await api("library");
  state.librarySort = "Recent";
  state.library = data.items;
  data.items.forEach(keep);
  const list = $("side-library");
  list.replaceChildren();
  data.items
    .filter((i) => i.type === "radio" || i.type === "playlist")
    .slice(0, 8)
    .forEach((i) => {
      const b = el("button", "side-collection");
      b.append(image(i.image), el("span", "", i.title));
      b.onclick = () => openItem(i);
      list.append(b);
    });
}
async function openItem(item, push = true) {
  if (item.type === "song") {
    playList([item], 0, null);
    return;
  }
  savePosition();
  if (push) state.history.push(state.view);
  state.view = `collection:${item.key}`;
  state.viewCollection = item;
  const generation = ++state.renderGeneration;
  loading();
  try {
    const data = await api(
      item.type === "artist" ? "artistPage" : "collection",
      { key: item.key },
    );
    if (generation !== state.renderGeneration) return;
    renderCollection(data);
    restorePosition();
  } catch (e) {
    if (generation === state.renderGeneration) errorPage(e.message);
  }
}
function recentSearches() {
  try {
    return JSON.parse(localStorage.getItem("uspot-search-recents") || "[]")
      .filter((x) => typeof x === "string")
      .slice(0, 10);
  } catch {
    return [];
  }
}
function renderRecentSearches() {
  const recent = recentSearches();
  if (!recent.length) return;
  const group = el("section", "section");
  const head = el("div", "section-heading");
  head.append(
    el("h2", "", "Recent searches"),
    button(
      "Clear all",
      () => {
        localStorage.removeItem("uspot-search-recents");
        group.remove();
      },
      "quiet",
    ),
  );
  group.append(head);
  recent.forEach((q) => {
    const row = el("div", "recent-search");
    row.append(
      button(
        q,
        () => {
          $("search").value = q;
          search();
        },
        "menu-action",
      ),
      button(
        "×",
        () => {
          localStorage.setItem(
            "uspot-search-recents",
            JSON.stringify(recentSearches().filter((x) => x !== q)),
          );
          row.remove();
        },
        "circle",
      ),
    );
    group.append(row);
  });
  $("content").append(group);
}
async function search(initialType = "All") {
  const q = $("search").value.trim();
  if (!q) return;
  state.view = "search";
  state.viewCollection = null;
  state.searchType = initialType;
  const generation = ++state.renderGeneration;
  localStorage.setItem(
    "uspot-search-recents",
    JSON.stringify(
      [q, ...recentSearches().filter((x) => x !== q)].slice(0, 10),
    ),
  );
  loading();
  try {
    const data = await api("search", { q });
    if (generation !== state.renderGeneration) return;
    function render(type) {
      state.searchType = type;
      $("content").replaceChildren(
        el("span", "eyebrow", "FIND YOUR NEXT FAVORITE"),
        el("h1", "", `Results for “${q}”`),
      );
      const filters = el("div", "filter-row search-filters");
      [
        "All",
        "Songs",
        "Albums",
        "Artists",
        "Playlists",
        "USpot Radios",
      ].forEach((label) =>
        filters.append(
          button(
            label,
            () => render(label),
            "chip " + (type === label ? "active" : ""),
          ),
        ),
      );
      $("content").append(filters);
      const create = el("div", "search-actions");
      create.append(
        button(
          "Create Radio",
          () => radioCreator(false),
          "transparent-control",
        ),
        button(
          "Create Radio Mix",
          () => radioCreator(true),
          "transparent-control",
        ),
      );
      $("content").append(create);
      if (type === "All") {
        const top =
          data.songs?.[0] ||
          data.artists?.[0] ||
          data.playlists?.[0] ||
          data.albums?.[0];
        if (top) {
          const featured = el("section", "top-result");
          featured.append(el("span", "eyebrow", "TOP RESULT"), card(top));
          featured.append(
            button(
              top.type === "song" ? "Play" : "Open",
              () => openItem(top),
              "primary top-play",
            ),
          );
          $("content").append(featured);
        }
      }
      if (type === "All" || type === "Songs") {
        if (data.songs?.length)
          $("content").append(el("h2", "search-heading", "Songs"));
        rows(data.songs || []);
      }
      if (type === "All" || type === "Artists")
        section("Artists", data.artists || []);
      if (type === "All" || type === "Albums")
        section("Albums", data.albums || []);
      if (type === "All" || type === "Playlists")
        section("Spotify playlists", data.playlists || []);
      if (type === "All" || type === "USpot Radios")
        section("Your USpot Radios", data.radios || []);
      const groups =
        type === "USpot Radios"
          ? [data.radios]
          : type === "Songs"
            ? [data.songs]
            : type === "Artists"
              ? [data.artists]
              : type === "Albums"
                ? [data.albums]
                : type === "Playlists"
                  ? [data.playlists]
                  : [
                      data.songs,
                      data.artists,
                      data.albums,
                      data.playlists,
                      data.radios,
                    ];
      if (!groups.some((g) => g?.length))
        $("content").append(
          el("p", "empty", "No matches here. Try a song title or artist name."),
        );
    }
    render(initialType);
    restorePosition();
  } catch (e) {
    if (generation === state.renderGeneration) errorPage(e.message);
  }
}

function shuffled(items) {
  const a = [...items];
  for (let i = a.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1));
    [a[i], a[j]] = [a[j], a[i]];
  }
  return a;
}
function playList(songs, index, collection) {
  if (!songs.length) {
    toast("No songs available yet.");
    return;
  }
  state.nextReady = null;
  state.manual.clear();
  state.smartKeys.clear();
  state.smart = false;
  state.queue = [...songs];
  state.original = [...songs];
  state.collection = collection;
  state.shuffle = collection?.type === "radio" && !!collection.shuffled;
  state.index = index;
  state.retries = 0;
  loadTrack(index);
}
async function loadTrack(index, retry = false) {
  if (!state.queue[index]) return;
  state.index = index;
  const song = state.queue[index];
  const generation = ++state.generation;
  clearTimeout(state.preloadTimer);
  clearTimeout(state.recoveryTimer);
  audio.pause();
  audio.removeAttribute("src");
  audio.load();
  $("elapsed").textContent = "0:00";
  $("duration").textContent = "—:—";
  $("seek").value = "0";
  state.current = song;
  state.manual.delete(song.key);
  state.audible = 0;
  state.lastTick = 0;
  state.qualified = false;
  state.started = false;
  state.ticket = "";
  renderPlayer();
  $("artist").textContent = "Connecting…";
  try {
    let ready;
    if (!retry && state.nextReady?.key === song.key) {
      try {
        ready = await state.nextReady.promise;
      } catch (error) {
        if (generation !== state.generation) return;
        state.nextReady = null;
        ready = await api("resolve", {
          key: song.key,
          collection: state.collection?.key || "",
          retry: true,
        });
      }
    } else
      ready = await api("resolve", {
        key: song.key,
        collection: state.collection?.key || "",
        retry,
      });
    if (generation !== state.generation) return;
    state.nextReady = null;
    state.ticket = ready.ticket;
    audio.src = ready.url;
    audio.load();
    $("artist").textContent = state.recoveryTimer
      ? "Waiting for audio · retrying…"
      : song.subtitle;
    await audio.play();
  } catch (e) {
    if (generation !== state.generation) return;
    toast(e.message);
    renderPlayer();
    if (e.name === "NotAllowedError") {
      $("artist").textContent = "Press Play to start audio";
    } else {
      scheduleStreamRetry(generation);
    }
  }
}
function scheduleStreamRetry(generation) {
  clearTimeout(state.recoveryTimer);
  if (state.retries >= 2) {
    $("artist").textContent = "Could not start · press Play to retry";
    return;
  }
  $("artist").textContent = "Stream unavailable · retrying in 10 seconds…";
  state.recoveryTimer = setTimeout(() => {
    if (generation !== state.generation) return;
    state.retries++;
    loadTrack(state.index, true);
  }, 10000);
}
function renderPlayer() {
  const song = state.current;
  if (song) {
    $("title").textContent = song.title;
    $("artist").textContent = song.subtitle;
    $("cover").hidden = !song.image;
    if (song.image) $("cover").src = song.image;
    $("start-radio").disabled = false;
    $("like-current").disabled = false;
    $("current-menu").disabled = false;
    $("like-current").textContent = song.liked ? "♥" : "♡";
    $("like-current").classList.toggle("active", !!song.liked);
    $("start-radio").classList.toggle(
      "active",
      state.collection?.type === "radio",
    );
    const now = $("now-card");
    now.replaceChildren(
      image(song.image),
      el("span", "eyebrow", state.collection?.title || "PLAYING FROM SEARCH"),
      el("h3", "", song.title),
      el("p", "", song.subtitle),
    );
    if (!audio.paused) {
      const indicator = el("div", "now-playing-label");
      indicator.append(el("span", "", "Now playing"), bars());
      now.append(indicator);
    }
  }
  state.playing = !audio.paused;
  $("play").textContent = state.playing ? "Ⅱ" : "▶";
  $("shuffle").classList.toggle("active", state.shuffle);
  $("repeat").classList.toggle("active", state.repeat);
  document.body.classList.toggle("paused", !state.playing);
  renderQueue();
  syncCollectionButtons();
  updateFullPlayer();
  highlight();
  if (
    "mediaSession" in navigator &&
    typeof MediaMetadata !== "undefined" &&
    song
  ) {
    navigator.mediaSession.metadata = new MediaMetadata({
      title: song.title,
      artist: song.subtitle,
      album: state.collection?.title || "USpot",
      artwork: song.image ? [{ src: song.image }] : [],
    });
  }
}
function highlight() {
  document
    .querySelectorAll("[data-key]")
    .forEach((n) =>
      n.classList.toggle(
        "active",
        n.dataset.key === state.current?.key ||
          (n.dataset.key === state.collection?.key && state.playing),
      ),
    );
}
function enqueue(song) {
  if (state.index < 0) {
    playList([song], 0, null);
    return;
  }
  if (song.key === state.current?.key || state.manual.has(song.key)) {
    toast("Already in queue");
    return;
  }
  const previous = state.queue.findIndex(
    (s, i) => i > state.index && s.key === song.key,
  );
  if (previous >= 0) state.queue.splice(previous, 1);
  let at = state.index + 1;
  while (state.queue[at] && state.manual.has(state.queue[at].key)) at++;
  state.queue.splice(at, 0, song);
  state.manual.add(song.key);
  state.nextReady = null;
  renderQueue();
  persistRadio();
  toast("Added to queue");
  prepareNext();
}
function prepareNext() {
  if (!state.current) return;
  const song = state.queue[state.index + 1];
  if (song && state.nextReady?.key !== song.key) {
    const promise = api("resolve", {
      key: song.key,
      collection: state.collection?.key || "",
    });
    promise.catch(() => {});
    state.nextReady = { key: song.key, promise };
  }
}
async function refill() {
  if (
    state.collection?.type !== "radio" ||
    state.refilling ||
    state.queue.length - state.index > 6
  )
    return;
  state.refilling = true;
  try {
    const key = state.collection.key;
    const data = await api("refreshRadio", { key });
    if (state.collection?.key !== key) return;
    const recent = new Set(
      state.queue.slice(Math.max(0, state.index - 60)).map((s) => s.key),
    );
    let fresh = data.songs.filter((s) => !recent.has(s.key));
    if (!fresh.length) {
      const queued = new Set(state.queue.slice(state.index).map((s) => s.key));
      const lastFew = new Set(
        state.queue
          .slice(Math.max(0, state.index - 4), state.index + 1)
          .map((s) => s.key),
      );
      let pool = data.songs.filter(
        (s) => !queued.has(s.key) && !lastFew.has(s.key),
      );
      if (!pool.length) pool = data.songs.filter((s) => !queued.has(s.key));
      fresh = shuffled(pool).slice(0, 10);
    }
    fresh.forEach(keep);
    state.queue.push(
      ...fresh.slice(
        0,
        Math.max(0, 10 - (state.queue.length - state.index - 1)),
      ),
    );
    renderQueue();
    persistRadio();
  } catch (e) {
    toast("Radio is waiting for the phone’s connection.");
  } finally {
    state.refilling = false;
  }
}
async function next() {
  const generation = state.generation;
  await refill();
  if (generation !== state.generation) return;
  if (state.index + 1 < state.queue.length) {
    state.retries = 0;
    loadTrack(state.index + 1);
  } else if (state.collection?.type === "radio") {
    toast("Finding more for your station…");
    await refill();
    if (state.index + 1 < state.queue.length) loadTrack(state.index + 1);
    else
      toast("No new radio songs returned. Refresh the station to try again.");
  } else if (state.repeat && state.queue.length) {
    state.retries = 0;
    loadTrack(0);
  } else {
    audio.pause();
    toast("You’ve reached the end of this queue.");
  }
}
async function createRadio(song) {
  const screenGeneration = state.renderGeneration;
  const generation = state.generation;
  const preserve =
    state.current?.key === song.key && state.ticket && !audio.error;
  toast("Creating your station…");
  try {
    const data = await api("radio", { key: song.key });
    if (generation !== state.generation) {
      toast("Radio saved. Open Your radios to listen.");
      return;
    }
    let adopted;
    if (preserve) {
      adopted = await api("adopt", {
        ticket: state.ticket,
        collection: data.item.key,
      });
      if (generation !== state.generation) return;
    }
    if (screenGeneration === state.renderGeneration) {
      state.renderGeneration++;
      state.history.push(state.view);
      state.view = `collection:${data.item.key}`;
      state.viewCollection = data.item;
      renderCollection(data);
    }
    if (adopted) {
      state.queue = [song, ...data.songs.filter((s) => s.key !== song.key)];
      state.original = [...state.queue];
      state.index = 0;
      state.collection = data.item;
      state.nextReady = null;
      state.ticket = adopted.ticket;
      state.audible = 0;
      state.lastTick = audio.paused ? 0 : performance.now();
      state.qualified = false;
      state.started = false;
      if (!audio.paused) {
        state.started = true;
        api("started", { ticket: state.ticket }).catch(() => {});
      }
      renderPlayer();
      persistRadio();
      prepareNext();
    } else playList(data.songs, 0, data.item);
  } catch (e) {
    toast(e.message);
  }
}
audio.addEventListener("playing", () => {
  clearTimeout(state.recoveryTimer);
  state.recoveryTimer = null;
  state.lastTick = performance.now();
  renderPlayer();
  persistRadio();
  refreshLike();
  if (!state.started && state.ticket) {
    state.started = true;
    api("started", { ticket: state.ticket }).catch(() => {});
  }
  clearTimeout(state.preloadTimer);
  state.preloadTimer = setTimeout(prepareNext, 2000);
  refill();
});
audio.addEventListener("pause", () => {
  clearTimeout(state.recoveryTimer);
  state.lastTick = 0;
  renderPlayer();
  persistRadio();
});
audio.addEventListener("waiting", () => {
  state.lastTick = 0;
  $("artist").textContent = "Buffering…";
});
audio.addEventListener("ended", next);
audio.addEventListener("timeupdate", () => {
  const duration = Number.isFinite(audio.duration) ? audio.duration : 0;
  $("elapsed").textContent = time(audio.currentTime);
  $("duration").textContent = duration > 0 ? time(duration) : "—:—";
  $("seek").value = duration
    ? String((audio.currentTime / duration) * 1000)
    : "0";
  const now = performance.now();
  if (!audio.paused && !audio.seeking && audio.readyState >= 3) {
    if (state.lastTick) state.audible += Math.min(1500, now - state.lastTick);
    state.lastTick = now;
    if (state.audible >= 30000) state.retries = 0;
    if (state.audible >= 30000 && !state.qualified && state.ticket) {
      state.qualified = true;
      api("listened", { ticket: state.ticket }).catch(() => {
        state.qualified = false;
      });
    }
  } else state.lastTick = 0;
});
audio.addEventListener("error", () => {
  if (!state.current || !audio.error) return;
  toast("The stream could not play. Trying a fresh lookup…");
  scheduleStreamRetry(state.generation);
});
$("play").onclick = () => {
  if (!state.current) {
    toast("Choose a song first.");
    return;
  }
  if (!audio.src || audio.error) {
    state.retries = 0;
    loadTrack(state.index, true);
    return;
  }
  if (audio.paused)
    audio
      .play()
      .catch(() => toast("Press Play again, or select the song again."));
  else audio.pause();
};
$("next").onclick = next;
$("previous").onclick = () => {
  if (audio.currentTime > 3) audio.currentTime = 0;
  else if (state.index > 0) {
    state.retries = 0;
    loadTrack(state.index - 1);
  }
};
$("seek").oninput = () => {
  if (Number.isFinite(audio.duration))
    audio.currentTime = (Number($("seek").value) / 1000) * audio.duration;
  state.lastTick = 0;
};
$("volume").oninput = () => (audio.volume = Number($("volume").value));
audio.volume = 0.8;
$("shuffle").onclick = () => cycleShuffle().catch((e) => toast(e.message));
$("repeat").onclick = () => {
  state.repeat = !state.repeat;
  renderPlayer();
};
$("start-radio").onclick = () => {
  if (state.collection?.type === "radio") openItem(state.collection);
  else if (state.current) createRadio(state.current);
};
$("clear-queue").onclick = () => {
  state.queue = state.queue.slice(0, state.index + 1);
  state.manual.clear();
  state.nextReady = null;
  renderQueue();
  persistRadio();
};
$("search").onkeydown = (e) => {
  if (e.key === "Enter") search();
};
$("back").onclick = () => {
  const view = state.history.pop() || "home";
  navigate(view, false);
};
document
  .querySelectorAll("[data-view]")
  .forEach((n) => (n.onclick = () => navigate(n.dataset.view)));
document.querySelectorAll("[data-special]").forEach(
  (n) =>
    (n.onclick = () => {
      const liked = n.dataset.special === "liked";
      openItem({
        key: liked ? "playlist:liked-songs" : "playlist:downloaded-offline",
        type: "playlist",
        title: liked ? "Liked Songs" : "Downloaded",
        image: "",
        subtitle: "Playlist",
      });
    }),
);
document.querySelector(".brand").onclick = (e) => {
  e.preventDefault();
  navigate("home");
};
$("help").onclick = () => $("help-dialog").showModal();
$("close-help").onclick = () => $("help-dialog").close();
$("theme").onclick = () => {
  const theme =
    document.documentElement.dataset.theme === "light" ? "dark" : "light";
  document.documentElement.dataset.theme = theme;
  localStorage.setItem("uspot-theme", theme);
};
document.documentElement.dataset.theme =
  localStorage.getItem("uspot-theme") || "dark";
if ("mediaSession" in navigator) {
  for (const [name, handler] of Object.entries({
    play: () => $("play").click(),
    pause: () => audio.pause(),
    nexttrack: next,
    previoustrack: () => $("previous").click(),
  })) {
    try {
      navigator.mediaSession.setActionHandler(name, handler);
    } catch {}
  }
}
// Desktop controls use the same Android repositories and station preferences.
let persistTimer,
  likeGeneration = 0,
  activeDialogVersion = 0;
function button(label, handler, cls = "quiet") {
  const b = el("button", cls, label);
  b.onclick = () =>
    Promise.resolve()
      .then(handler)
      .catch((e) => toast(e.message));
  return b;
}
function dialog(title, description = "") {
  activeDialogVersion++;
  const d = $("action-dialog");
  d.classList.remove("full-player");
  if (d.open) d.close();
  d.replaceChildren();
  const head = el("div", "section-heading");
  head.append(
    el("h2", "", title),
    button("×", () => d.close(), "circle"),
  );
  d.append(head);
  if (description) d.append(el("p", "muted", description));
  d.showModal();
  return d;
}
function confirmAction(title, message, handler) {
  const d = dialog(title, message);
  const actions = el("div", "dialog-actions");
  actions.append(
    button("Cancel", () => d.close(), "chip"),
    button(
      "Continue",
      async () => {
        d.close();
        await handler();
      },
      "primary",
    ),
  );
  d.append(actions);
}
function persistRadio() {
  clearTimeout(persistTimer);
  if (state.collection?.type !== "radio" || state.index < 0) return;
  const key = state.collection.key;
  const snapshot = state.queue
    .slice(state.index, state.index + 11)
    .map((s) => s.key);
  const manual = [...state.manual];
  const shuffled = state.shuffle;
  persistTimer = setTimeout(
    () =>
      api("saveRadioQueue", { key, songs: snapshot, manual, shuffled }).catch(
        () => {},
      ),
    250,
  );
}
function syncCollectionButtons() {
  document.querySelectorAll("[data-collection-shuffle]").forEach((n) => {
    const active =
      n.dataset.collectionShuffle === state.collection?.key && state.shuffle;
    n.classList.toggle("active", active);
    n.setAttribute("aria-pressed", String(active));
  });
  document.querySelectorAll("[data-collection-play]").forEach((n) => {
    const active =
      n.dataset.collectionPlay === state.collection?.key && !audio.paused;
    n.textContent = active ? "Ⅱ Pause" : "▶ Play";
    n.setAttribute(
      "aria-label",
      active ? "Pause collection" : "Play collection",
    );
  });
  $("refresh-radio").hidden = state.collection?.type !== "radio";
  $("shuffle").textContent = state.smart ? "⤨ ◇" : "⤨";
  $("shuffle").title = state.smart
    ? "Smart shuffle"
    : state.shuffle
      ? "Shuffle on"
      : "Shuffle off";
}
function collectionActions(data) {
  const actions = el("div", "collection-actions");
  const item = data.item;
  const play = button(
    "▶ Play",
    () => {
      if (state.collection?.key === item.key) {
        $("play").click();
        return;
      }
      playList(data.songs, 0, item);
    },
    "primary",
  );
  play.dataset.collectionPlay = item.key;
  const shuffle = button(
    "⤨ Shuffle",
    () => {
      if (state.collection?.key === item.key) {
        cycleShuffle();
        return;
      }
      const mixed = shuffled(data.songs);
      if (mixed.length > 1 && mixed[0].key === data.songs[0].key)
        [mixed[0], mixed[1]] = [mixed[1], mixed[0]];
      playList(mixed, 0, item);
      state.original = [...data.songs];
      state.shuffle = true;
      renderPlayer();
      persistRadio();
    },
    "transparent-control",
  );
  shuffle.dataset.collectionShuffle = item.key;
  actions.append(
    play,
    shuffle,
    button(
      "↓ Download",
      () => downloadSongs(data.songs),
      "transparent-control",
    ),
    button("•••", () => collectionMenu(item), "quiet"),
  );
  return actions;
}
function renderCollection(data) {
  const item = keep(data.item);
  state.viewCollection = item;
  state.viewData = data;
  $("content").replaceChildren();
  const hero = el("section", "collection-hero");
  const art = item.image
    ? image(item.image)
    : el(
        "div",
        "art-empty",
        item.key === "playlist:liked-songs"
          ? "♡"
          : item.key === "playlist:downloaded-offline"
            ? "↓"
            : "♫",
      );
  hero.append(art);
  const info = el("div");
  info.append(
    el(
      "span",
      "eyebrow",
      item.type === "radio"
        ? "USPOT RADIO • CONTINUOUS RECOMMENDATIONS"
        : item.type.toUpperCase(),
    ),
    el("h1", "", item.title),
  );
  if (item.type === "radio") {
    info.append(
      el(
        "p",
        "muted",
        item.mix
          ? `${item.anchors?.length || 0} songs blended into one continuous station`
          : "A station that keeps going, shaped by your song.",
      ),
    );
  } else
    info.append(
      el("p", "muted", `${data.songs.length} songs · ${item.subtitle}`),
    );
  if (item.type !== "artist")
    info.append(
      button(
        item.pinned ? "Pinned" : "Pin to library",
        async () => {
          const result = await api("pin", { key: item.key });
          item.pinned = result.pinned;
          renderCollection(data);
        },
        "transparent-control",
      ),
    );
  hero.append(info);
  $("content").append(hero, collectionActions(data));
  if (item.key === "playlist:downloaded-offline")
    $("content").append(
      button("Manage downloads", () => navigate("downloads"), "chip"),
    );
  if (item.type === "radio") {
    const tuning = el("section", "radio-tuning");
    tuning.append(el("h2", "", "Tune your radio"));
    const controls = el("div", "tuning-controls");
    [
      ["close", "Stay close"],
      ["balanced", "Balanced"],
      ["explore", "Explore more"],
    ].forEach(([mode, label]) => {
      const b = button(
        label,
        () => tuneRadio(item, mode),
        "transparent-control " +
          ((item.mode || "balanced") === mode ? "active" : ""),
      );
      b.dataset.radioMode = mode;
      b.setAttribute(
        "aria-pressed",
        String((item.mode || "balanced") === mode),
      );
      controls.append(b);
    });
    tuning.append(
      controls,
      el(
        "p",
        "muted",
        item.mode === "close"
          ? "Your starting songs and their direct Spotify suggestions."
          : item.mode === "explore"
            ? "Newer songs guide more of what comes next."
            : "A mix of familiar sounds and fresh discoveries.",
      ),
    );
    tuning.append(
      button(
        "↻ Refresh upcoming songs",
        () => tuneRadio(item, null),
        "transparent-control",
      ),
    );
    const about = el("details", "radio-about");
    about.append(el("summary", "", "About this station"),
      el("p", "muted", "Continuous suggestions shaped by your starting songs and Radio tuning. Your station keeps its name and remembers where you left off. Similarity is not a genre guarantee."));
    wireDisclosure(about);
    tuning.append(about);
    $("content").append(tuning);
    if (item.mix) {
      const anchors = el("details", "section station-details");
      anchors.append(el("summary", "", "Songs in this mix"));
      item.anchors?.forEach((s) =>
        anchors.append(el("p", "anchor-name", `${s.title} · ${s.subtitle}`)),
      );
      $("content").append(anchors);
    }
    const note = el(
      "p",
      "radio-note",
      "Songs are chosen using Spotify suggestions based on your station. See what’s coming next in Queue.",
    );
    $("content").append(note);
  } else if (item.type === "artist") {
    const follow = button(
      data.followed ? "Following" : "Follow",
      async () => {
        await api("followArtist", { key: item.key, saved: !data.followed });
        data.followed = !data.followed;
        follow.textContent = data.followed ? "Following" : "Follow";
      },
      "chip",
    );
    $("content").append(
      follow,
      el(
        "p",
        "muted",
        `${Number(data.listeners || 0).toLocaleString()} monthly listeners`,
      ),
      el("h2", "", "Popular"),
    );
    rows(data.songs);
    section("Popular releases", data.releases);
    section("Appears on", data.appearsOn);
    section("Fans also like", data.related);
    if (data.bio)
      $("content").append(
        el("h2", "", "About"),
        el("p", "artist-bio", data.bio.replace(/<[^>]*>/g, "")),
      );
  } else {
    rows(data.songs);
    if (item.canRecommend) playlistRecommendations(item);
  }
  syncCollectionButtons();
  highlight();
}
async function tuneRadio(item, mode) {
  const renderVersion = state.renderGeneration;
  const playbackVersion = state.generation;
  const currentKey = state.collection?.key;
  const d = await api(mode ? "tuneRadio" : "refreshRadio", {
    key: item.key,
    ...(mode ? { mode } : {}),
  });
  keep(d.item);
  d.songs.forEach(keep);
  if (
    state.collection?.key === item.key &&
    currentKey === item.key &&
    playbackVersion === state.generation
  ) {
    const protectedQueue = state.queue.slice(0, state.index + 1);
    const manual = state.queue
      .slice(state.index + 1)
      .filter((s) => state.manual.has(s.key));
    const keys = new Set(
      [...protectedQueue.slice(-5), ...manual].map((s) => s.key),
    );
    const next = d.songs.filter((s) => !keys.has(s.key));
    state.queue = [
      ...protectedQueue,
      ...manual,
      ...(state.shuffle ? shuffled(next) : next).slice(
        0,
        Math.max(0, 10 - manual.length),
      ),
    ];
    state.original = [...state.queue];
    state.collection = d.item;
    state.nextReady = null;
    renderPlayer();
    persistRadio();
    prepareNext();
  }
  if (
    renderVersion === state.renderGeneration &&
    state.viewCollection?.key === item.key
  )
    renderCollection(d);
  toast(mode ? "Radio tuning updated" : "Radio queue refreshed");
}
function downloadSongs(songs) {
  if (!songs.length) {
    toast("No songs to download");
    return;
  }
  confirmAction(
    "Download on your phone?",
    `Save ${songs.length} songs using USpot’s existing download manager. Downloads are stored on the phone, and can stream to this computer while desktop mode is connected.`,
    async () => {
      await api("download", { songs: songs.map((s) => s.key) });
      toast("Downloads added on your phone");
    },
  );
}
function updateSong(track) {
  if (!track) return;
  keep(track);
  for (const list of [state.queue, state.original])
    for (let i = 0; i < list.length; i++)
      if (list[i].key === track.key) list[i] = { ...list[i], ...track };
  if (state.current?.key === track.key)
    state.current = { ...state.current, ...track };
  renderPlayer();
}
async function refreshLike() {
  if (!state.current) return;
  const key = state.current.key;
  const version = ++likeGeneration;
  try {
    const data = await api("songState", { key });
    if (version === likeGeneration && state.current?.key === key)
      updateSong(data.track);
  } catch {}
}
async function toggleLike(song) {
  const data = await api("like", { key: song.key, saved: !song.liked });
  Object.assign(song, data.track);
  updateSong(data.track);
  document.querySelectorAll("[data-like-key]").forEach((b) => {
    if (b.dataset.likeKey === song.key) {
      b.textContent = song.liked ? "♥" : "♡";
      b.classList.toggle("active", !!song.liked);
      b.ariaLabel = `${song.liked ? "Unlike" : "Like"} ${song.title}`;
    }
  });
  toast(song.liked ? "Added to Liked Songs" : "Removed from Liked Songs");
}
function songMenu(song) {
  song = nodes.get(song.key) || song;
  const d = dialog(song.title, song.subtitle);
  const actions = [
    ["Play next / Add to queue", () => enqueue(song)],
    [
      song.liked ? "Remove from Liked Songs" : "Add to Liked Songs",
      () => toggleLike(song),
    ],
    ["Add to playlist", () => playlistPicker(song)],
    ["Start USpot Radio", () => createRadio(song)],
    [
      song.downloaded ? "Remove download from phone" : "Download on phone",
      () => {
        if (song.downloaded)
          confirmAction(
            "Remove downloaded song?",
            "This removes the offline file from your phone. The song remains in Spotify.",
            async () => {
              await api("removeDownload", { key: song.key });
              song.downloaded = false;
              toast("Download removed");
            },
          );
        else downloadSongs([song]);
      },
    ],
    [
      "Less like this",
      async () => {
        await api("exclude", { key: song.key });
        filterExcluded(song, false);
        toast("We’ll play less like this");
      },
    ],
    [
      "Don’t play this artist",
      async () => {
        await api("exclude", { key: song.key, artist: true });
        filterExcluded(song, true);
        toast("Artist excluded from recommendations");
      },
    ],
    ["View artist", () => searchRelated(song.subtitle, "Artists")],
    ["View album", () => searchRelated(song.album || song.title, "Albums")],
    ["Lyrics", () => showLyrics(song)],
  ];
  if (state.view === "history")
    actions.push([
      "Remove from listening history",
      async () => {
        await api("removeHistory", { key: song.key, at: song.at });
        navigate("history", false);
      },
    ]);
  actions.forEach(([label, handler]) =>
    d.append(
      button(
        label,
        async () => {
          d.close();
          await handler();
        },
        "menu-action",
      ),
    ),
  );
}
function filterExcluded(song, artist) {
  const name = song.subtitle?.split(",")[0]?.trim().toLowerCase();
  state.queue = state.queue.filter(
    (s, i) =>
      i <= state.index ||
      state.manual.has(s.key) ||
      !(artist
        ? s.subtitle?.split(",")[0]?.trim().toLowerCase() === name
        : s.key === song.key),
  );
  state.nextReady = null;
  renderQueue();
  persistRadio();
  prepareNext();
}
function searchRelated(query, type) {
  $("search").value = query;
  search(type);
}
function collectionMenu(item) {
  const d = dialog(item.title, item.subtitle);
  if (["playlist", "album", "radio"].includes(item.type))
    d.append(
      button(
        item.pinned ? "Unpin from library" : "Pin to library",
        async () => {
          const changed = await api("pin", { key: item.key });
          item.pinned = changed.pinned;
          d.close();
          toast(changed.pinned ? "Pinned to your library" : "Unpinned");
          await loadLibrary();
          if (state.view === "library" || state.view === "radios")
            navigate(state.view, false);
        },
        "menu-action",
      ),
    );
  d.append(
    button(
      "Remove from Home history",
      async () => {
        await api("hideHome", { key: item.key });
        d.close();
        if (state.view === "home") navigate("home", false);
        toast("Removed from Home history");
        const undo = button(
          "Undo",
          async () => {
            await api("restoreHome", { key: item.key });
            $("toast").hidden = true;
            if (state.view === "home") navigate("home", false);
          },
          "quiet",
        );
        $("toast").append(undo);
      },
      "menu-action",
    ),
  );
  if (
    ["playlist", "album"].includes(item.type) &&
    !["playlist:liked-songs", "playlist:downloaded-offline"].includes(item.key)
  )
    d.append(
      button(
        "Remove from your library",
        () => {
          d.close();
          confirmAction(
            item.type === "album"
              ? "Remove saved album?"
              : "Unfollow playlist?",
            `This removes “${item.title}” from your Spotify library. This cannot be undone automatically. You can save or follow it again later. The playlist itself will not be deleted.`,
            async () => {
              await api("removeLibrary", { key: item.key });
              toast("Removed from your library");
              await loadLibrary();
              navigate("library", false);
            },
          );
        },
        "menu-action",
      ),
    );
}
function renderLibraryItems(items, view) {
  const head = el("div", "section-heading");
  head.append(
    el(
      "h2",
      "",
      view === "radios" ? "Saved USpot stations" : "Collections",
    ),
    button("+", view === "radios" ? radioChoice : createPlaylist, "circle"),
  );
  $("content").append(head);
  const filters = el("div", "filter-row"),
    holder = el("section", "section");
  function show(type) {
    const utilities = $("content").querySelector(".library-utilities");
    if (utilities) utilities.hidden = type !== "All";
    holder.replaceChildren();
    let subset = items.filter((i) => type === "All" || i.type === type);
    if ((state.librarySort || "Recent") === "Name")
      subset.sort((a, b) => a.title.localeCompare(b.title));
    if (state.librarySort === "Creator")
      subset.sort((a, b) => (a.subtitle || "").localeCompare(b.subtitle || ""));
    subset.sort((a, b) => Number(!!b.pinned) - Number(!!a.pinned));
    const grid = el("div", "cards");
    subset.forEach((i) => grid.append(card(i)));
    holder.append(grid);
    if (!subset.length) holder.append(el("p", "empty", "Nothing here yet."));
  }
  if (view !== "radios")
    for (const [type, label] of [
      ["All", "All"],
      ["playlist", "Playlists"],
      ["album", "Albums"],
      ["radio", "USpot Radios"],
    ])
      filters.append(
        button(
          label,
          () => {
            filters
              .querySelectorAll("button")
              .forEach((b) =>
                b.classList.toggle("active", b.textContent === label),
              );
            show(type);
          },
          "chip",
        ),
      );
  if (view !== "radios") {
    const utilities = el("div", "library-utilities");
    [["Listening history", "Your recent plays", "history"], ["Local files", "Music on this device", "local"]].forEach(([title, subtitle, view]) => {
      const row = button("", () => navigate(view), "library-utility");
      const words = el("span");
      words.append(el("strong", "", title), el("small", "muted", subtitle));
      row.append(el("span", "utility-icon", view === "history" ? "↶" : "♫"), words, el("span", "utility-chevron", "›"));
      utilities.append(row);
    });
    $("content").append(utilities);
  }
  $("content").append(filters, holder);
  show("All");
}
async function createPlaylist() {
  const d = dialog(
    "Create a playlist",
    "Create a playlist in your connected Spotify account.",
  );
  const name = el("input", "text-input");
  name.placeholder = "My playlist";
  name.maxLength = 100;
  name.ariaLabel = "Playlist name";
  const create = button(
    "Create playlist",
    async () => {
      if (!name.value.trim()) return;
      create.disabled = true;
      try {
        const data = await api("createPlaylist", { name: name.value });
        keep(data.item);
        d.close();
        if (data.warning) toast(data.warning);
        await loadLibrary();
        openItem(data.item);
      } finally {
        create.disabled = false;
      }
    },
    "primary",
  );
  d.append(name, create);
  name.focus();
}
async function playlistPicker(song) {
  const d = dialog("Add to playlist", song.title);
  const version = activeDialogVersion;
  const wait = el("p", "muted", "Loading your playlists…");
  d.append(wait);
  await loadLibrary();
  if (!d.open || activeDialogVersion !== version) return;
  wait.remove();
  state.library
    .filter(
      (i) =>
        i.type === "playlist" &&
        !["playlist:liked-songs", "playlist:downloaded-offline"].includes(
          i.key,
        ),
    )
    .forEach((i) =>
      d.append(
        button(
          i.title,
          async () => {
            const result = await api("addToPlaylist", {
              key: song.key,
              playlist: i.key,
            });
            d.close();
            toast(result.warning || "Added to playlist");
          },
          "menu-action",
        ),
      ),
    );
  d.append(button("+ Create playlist", createPlaylist, "menu-action"));
}
function radioChoice() {
  const d = dialog("Create a station");
  d.append(
    button("USpot Radio", () => radioCreator(false), "menu-action"),
    button("USpot Radio Mix", () => radioCreator(true), "menu-action"),
  );
}
async function radioCreator(mix) {
  const d = dialog(
    mix ? "Create USpot Radio Mix" : "Create USpot Radio",
    mix
      ? "Choose up to 5 songs. Search Spotify or pick from your playlists and Liked Songs."
      : "Choose one song to start your station.",
  );
  const version = ++activeDialogVersion;
  const chosen = new Map();
  const name = el("input", "text-input");
  name.placeholder = mix
    ? "Name your mix (optional)"
    : "Radio name follows your song";
  name.hidden = !mix;
  const field = el("input", "text-input");
  field.placeholder = "Search Spotify songs";
  field.ariaLabel = "Search songs for radio";
  const selection = el("div", "selection-summary", "0 songs selected");
  const results = el("div", "picker-tracks");
  const playlists = el("div", "picker-playlists");
  const create = button(
    mix ? "Create Radio Mix" : "Create Radio",
    async () => {
      if (!chosen.size) return;
      create.disabled = true;
      try {
        const data = await api("createRadio", {
          songs: [...chosen.keys()],
          mix,
          name: name.value,
        });
        if (activeDialogVersion !== version) return;
        keep(data.item);
        d.close();
        state.history.push(state.view);
        state.view = `collection:${data.item.key}`;
        state.renderGeneration++;
        renderCollection(data);
        playList(data.songs, 0, data.item);
        loadLibrary().catch(() => {});
      } finally {
        create.disabled = false;
      }
    },
    "primary",
  );
  create.disabled = true;
  function update() {
    selection.replaceChildren(
      el("strong", "", `${chosen.size} / ${mix ? 5 : 1} songs selected`),
    );
    chosen.forEach((s) =>
      selection.append(
        button(
          `✓ ${s.title} ×`,
          () => {
            chosen.delete(s.key);
            update();
          },
          "selected-song",
        ),
      ),
    );
    results.querySelectorAll("[data-pick]").forEach((b) => {
      b.classList.toggle("active", chosen.has(b.dataset.pick));
      b.setAttribute("aria-pressed", String(chosen.has(b.dataset.pick)));
    });
    create.disabled = !chosen.size;
  }
  function showSongs(songs) {
    results.replaceChildren();
    songs.forEach((s) => {
      keep(s);
      const b = button(
        "",
        () => {
          if (chosen.has(s.key)) chosen.delete(s.key);
          else if (chosen.size < (mix ? 5 : 1)) chosen.set(s.key, s);
          else toast(`Choose up to ${mix ? 5 : 1} songs`);
          update();
        },
        "picker-track",
      );
      b.dataset.pick = s.key;
      b.append(
        image(s.image),
        el("span", "", `${s.title} · ${s.subtitle}`),
        el("strong", "", "✓"),
      );
      results.append(b);
    });
    update();
  }
  let searchVersion = 0;
  async function runSearch() {
    const q = field.value.trim();
    if (!q) return;
    const turn = ++searchVersion;
    results.replaceChildren(el("p", "muted", "Searching Spotify…"));
    const data = await api("search", { q });
    if (turn === searchVersion && version === activeDialogVersion)
      showSongs(data.songs);
  }
  field.onkeydown = (e) => {
    if (e.key === "Enter") runSearch().catch((e) => toast(e.message));
  };
  d.append(
    name,
    selection,
    create,
    field,
    button("Search", runSearch, "chip"),
    playlists,
    results,
  );
  await loadLibrary();
  if (version !== activeDialogVersion || !d.open) return;
  state.library
    .filter(
      (i) => i.type === "playlist" && i.key !== "playlist:downloaded-offline",
    )
    .forEach((i) =>
      playlists.append(
        button(
          i.title,
          async () => {
            const turn = ++searchVersion;
            const data = await api("collection", { key: i.key });
            if (turn === searchVersion && version === activeDialogVersion)
              showSongs(data.songs);
          },
          "chip",
        ),
      ),
    );
}
let shuffleBusy = false;
async function cycleShuffle() {
  if (shuffleBusy) return;
  shuffleBusy = true;
  try {
    await applyShuffle();
  } finally {
    shuffleBusy = false;
  }
}
async function applyShuffle() {
  if (!state.current) return;
  const special =
    state.collection?.type === "radio" ||
    state.collection?.key === "playlist:downloaded-offline";
  if (!state.shuffle) {
    state.shuffle = true;
    const manual = state.queue
      .slice(state.index + 1)
      .filter((s) => state.manual.has(s.key));
    state.queue = [
      ...state.queue.slice(0, state.index + 1),
      ...manual,
      ...shuffled(
        state.queue
          .slice(state.index + 1)
          .filter((s) => !state.manual.has(s.key)),
      ),
    ];
    state.smart = false;
  } else if (!state.smart && !special) {
    const generation = state.generation;
    const remaining = state.queue.slice(state.index + 1);
    if (!remaining.length) {
      toast(
        "Smart Shuffle needs a playlist, album or a queue with upcoming songs.",
      );
      state.shuffle = false;
    } else {
      const seeds = [state.current, ...remaining].filter(
        (s) => s.spotifyId || !s.key.startsWith("local:"),
      );
      const selected = [
        seeds[0],
        seeds[Math.floor(seeds.length / 2)],
        seeds.at(-1),
      ].filter(Boolean);
      const data = await api("smartSuggestions", {
        songs: [...new Set(selected.map((s) => s.key))].slice(0, 3),
      });
      if (generation !== state.generation) return;
      const known = new Set(state.queue.map((s) => s.key));
      const suggestions = data.songs.filter((s) => !known.has(s.key));
      let count = 0,
        ordinary = 0;
      const upcoming = [];
      remaining.forEach((s, index) => {
        upcoming.push(s);
        if (!state.manual.has(s.key)) ordinary++;
        if (
          (ordinary >= 3 || index === remaining.length - 1) &&
          count < 5 &&
          suggestions.length
        ) {
          const suggested = keep(suggestions.shift());
          upcoming.push(suggested);
          state.smartKeys.add(suggested.key);
          count++;
          ordinary = 0;
        }
      });
      state.queue = [...state.queue.slice(0, state.index + 1), ...upcoming];
      state.smart = true;
      if (!count) toast("No additional suggestions returned.");
    }
  } else {
    state.shuffle = false;
    state.smart = false;
    const past = state.queue.slice(0, state.index + 1),
      heard = new Set(past.map((s) => s.key));
    const remaining = state.queue
      .slice(state.index + 1)
      .filter((s) => !state.smartKeys.has(s.key) || state.manual.has(s.key));
    const manual = remaining.filter((s) => state.manual.has(s.key));
    state.queue = [
      ...past,
      ...manual,
      ...state.original.filter(
        (s) =>
          !heard.has(s.key) &&
          !state.manual.has(s.key) &&
          remaining.some((t) => t.key === s.key),
      ),
      ...remaining.filter(
        (s) =>
          !state.original.some((t) => t.key === s.key) &&
          !state.manual.has(s.key),
      ),
    ];
    state.smartKeys.clear();
  }
  state.nextReady = null;
  renderPlayer();
  persistRadio();
  prepareNext();
}
async function showLyrics(song) {
  const d = dialog("Lyrics", `${song.title} · ${song.subtitle}`);
  const version = activeDialogVersion;
  d.append(el("p", "muted", "Finding lyrics…"));
  const data = await api("lyrics", { key: song.key });
  if (!d.open || activeDialogVersion !== version) return;
  d.lastChild.remove();
  const lines = el("div", "lyrics");
  if (!data.lines.length)
    lines.append(el("p", "muted", "Lyrics are not available for this song."));
  data.lines.forEach((line) => {
    const b = button(
      line.text,
      () => {
        if (
          data.synced &&
          state.current?.key === song.key &&
          Number.isFinite(audio.duration)
        )
          audio.currentTime = line.at / 1000;
      },
      "lyric-line",
    );
    b.disabled = !data.synced;
    lines.append(b);
  });
  d.append(lines);
}
let sleepTimer = null,
  eqContext = null,
  eqFilters = [];
let eqEnabled = localStorage.getItem("uspot-eq-enabled") === "true";
let eqGains = (() => {
  try {
    const x = JSON.parse(localStorage.getItem("uspot-eq") || "[0,0,0,0,0]");
    return Array.isArray(x) && x.length === 5
      ? x.map((v) => Math.max(-12, Math.min(12, Number(v) || 0)))
      : [0, 0, 0, 0, 0];
  } catch {
    return [0, 0, 0, 0, 0];
  }
})();
function ensureEq() {
  const AudioContext = window.AudioContext || window.webkitAudioContext;
  if (!AudioContext)
    throw new Error("This browser does not support an equalizer.");
  if (!eqContext) {
    eqContext = new AudioContext();
    let previous = eqContext.createMediaElementSource(audio);
    eqFilters = [60, 230, 910, 3600, 14000].map((frequency, index) => {
      const filter = eqContext.createBiquadFilter();
      filter.type =
        index === 0 ? "lowshelf" : index === 4 ? "highshelf" : "peaking";
      filter.frequency.value = frequency;
      filter.Q.value = 0.8;
      previous.connect(filter);
      previous = filter;
      return filter;
    });
    previous.connect(eqContext.destination);
  }
  eqFilters.forEach((filter, index) =>
    filter.gain.setTargetAtTime(
      eqEnabled ? eqGains[index] : 0,
      eqContext.currentTime,
      0.03,
    ),
  );
  eqContext.resume();
  localStorage.setItem("uspot-eq-enabled", String(eqEnabled));
  localStorage.setItem("uspot-eq", JSON.stringify(eqGains));
}
function themePreference() {
  const follow = localStorage.getItem("uspot-follow-theme") !== "false";
  const dark = follow
    ? matchMedia("(prefers-color-scheme: dark)").matches
    : localStorage.getItem("uspot-theme") !== "light";
  document.documentElement.dataset.theme = dark ? "dark" : "light";
}
function settingRow(parent, title, description, control) {
  const r = el("div", "setting-row");
  const copy = el("div");
  copy.append(el("strong", "", title), el("small", "", description));
  r.append(copy, control);
  parent.append(r);
}
function checkbox(checked, handler) {
  const i = el("input");
  i.type = "checkbox";
  i.checked = checked;
  i.onchange = () =>
    Promise.resolve(handler(i.checked)).catch((e) => {
      i.checked = !i.checked;
      toast(e.message);
    });
  return i;
}
const disclosureMotions = new WeakMap();
function wireDisclosure(details) {
  const summary = details.querySelector("summary");
  if (!summary) return;
  summary.addEventListener("click", (event) => {
    if (!details.animate || window.matchMedia("(prefers-reduced-motion: reduce)").matches) return;
    event.preventDefault();
    const previous = disclosureMotions.get(details);
    const targetOpen = !(previous?.targetOpen ?? details.open);
    const start = details.getBoundingClientRect().height;
    previous?.animation.cancel();
    details.open = true;
    const style = getComputedStyle(details);
    const collapsed = summary.getBoundingClientRect().height + parseFloat(style.paddingTop || 0) + parseFloat(style.borderTopWidth || 0) + parseFloat(style.borderBottomWidth || 0);
    const finish = targetOpen ? details.scrollHeight : collapsed;
    details.style.overflow = "hidden";
    const animation = details.animate([{ height: `${start}px` }, { height: `${finish}px` }], { duration: 200, easing: "cubic-bezier(.2,.8,.2,1)" });
    const motion = { animation, targetOpen };
    disclosureMotions.set(details, motion);
    animation.finished.then(() => {
      if (disclosureMotions.get(details) !== motion) return;
      details.open = targetOpen;
      details.style.overflow = "";
      disclosureMotions.delete(details);
    }).catch(() => {});
  });
}
async function renderSettings(generation) {
  const data = await api("settings");
  if (generation !== state.renderGeneration) return;
  const content = $("content");
  content.replaceChildren(
    el("span", "eyebrow", "YOUR MUSIC, YOUR WAY"),
    el("h1", "", "Settings"),
  );
  const profile = el("div", "settings-profile");
  profile.append(el("h2", "", data.name || "Your account"));
  profile.append(button("USpot Premium  ›", () => {
    const d = dialog("USpot Premium", "Included with USpot. More music. More freedom.");
    const benefits = el("div", "premium-benefits");
    for (const [title, detail] of [
      ["No ads", "Enjoy your music without ad breaks."],
      ["Unlimited skips", "Find the song that fits your moment."],
      ["Offline downloads", "Take your favorites with you on your phone."],
      ["Radio & Radio Mix", "Explore from the songs you love."],
      ["And more", "Personal recommendations, listening stats, and desktop listening."]
    ]) {
      const row = el("div", "premium-benefit");
      row.append(el("strong", "", "✓ " + title), el("p", "muted", detail));
      benefits.append(row);
    }
    d.append(benefits, el("p", "muted", "These are USpot features. This does not change your Spotify subscription."), button("Keep listening", () => d.close(), "primary"));
  }, "premium-badge"));
  content.append(profile);
  function group(title) {
    const s = el("details", "settings-group");
    const summary = el("summary");
    summary.append(el("h2", "", title));
    s.append(summary);
    wireDisclosure(s);
    if (title === "Appearance") s.open = true;
    content.append(s);
    return s;
  }
  const storage = group("Storage");
  storage.append(el("p", "", "Temporary music & artwork limit: 256 MB"));
  if (Number.isFinite(data.managedCacheBytes)) storage.append(el("p", "muted", `${Math.round(data.managedCacheBytes / 1048576)} MB cached on your phone.`));
  storage.append(el("p", "muted", "Older cached files are removed automatically. Your listening history, stats, Radio progress, Home personalization, sign-ins and downloaded music are kept separately."));
  storage.append(el("p", "muted", "Files in use are protected. Android, web sign-in and update downloads can temporarily use additional cache space."));
  const web = group("Desktop & Browser");
  web.append(
    el(
      "p",
      "muted",
      `Connected to ${location.host}. Audio plays on this computer. Stop desktop mode using your phone’s Settings or running notification. Anyone on the same Wi-Fi can connect while it is running.`,
    ),
  );
  const appearance = group("Appearance");
  const follow = checkbox(
    localStorage.getItem("uspot-follow-theme") !== "false",
    (v) => {
      localStorage.setItem("uspot-follow-theme", String(v));
      themePreference();
      renderSettings(state.renderGeneration);
    },
  );
  settingRow(
    appearance,
    "Follow device theme",
    "Automatically use this computer’s light or dark theme.",
    follow,
  );
  const dark = checkbox(
    document.documentElement.dataset.theme !== "light",
    (v) => {
      localStorage.setItem("uspot-theme", v ? "dark" : "light");
      themePreference();
    },
  );
  dark.disabled = follow.checked;
  settingRow(
    appearance,
    "Dark mode",
    "Turn off for light mode when Follow device theme is disabled.",
    dark,
  );
  const playback = group("Playback");
  settingRow(
    playback,
    "Repeat collection",
    "Playlists and albums loop when their queue ends.",
    checkbox(state.repeat, (v) => {
      state.repeat = v;
      renderPlayer();
    }),
  );
  const timer = el("select", "text-input");
  [
    [0, "Off"],
    [15, "15 minutes"],
    [30, "30 minutes"],
    [60, "1 hour"],
  ].forEach(([value, label]) => {
    const o = el("option", "", label);
    o.value = value;
    timer.append(o);
  });
  timer.onchange = () => {
    clearTimeout(sleepTimer);
    const minutes = Number(timer.value);
    if (minutes)
      sleepTimer = setTimeout(() => {
        audio.pause();
        toast("Sleep timer finished");
      }, minutes * 60000);
    toast(minutes ? `Sleep timer: ${minutes} minutes` : "Sleep timer off");
  };
  settingRow(playback, "Sleep timer", "Pauses this browser’s playback.", timer);
  const equalizer = group("Equalizer");
  settingRow(
    equalizer,
    "Enable equalizer",
    "Applies to this computer’s audio. Phone audio settings are separate.",
    checkbox(eqEnabled, (v) => {
      eqEnabled = v;
      ensureEq();
    }),
  );
  const presets = {
    Normal: [0, 0, 0, 0, 0],
    "Bass Boost": [6, 4, 1, 0, 0],
    Rock: [4, 2, -1, 2, 4],
    Pop: [-1, 2, 4, 2, -1],
    Classical: [3, 1, -1, 1, 3],
    Vocal: [-2, 0, 4, 3, 0],
  };
  const preset = el("select", "text-input");
  Object.keys(presets).forEach((name) => preset.append(el("option", "", name)));
  const sliders = [];
  preset.onchange = () => {
    eqGains = [...presets[preset.value]];
    sliders.forEach((s, i) => (s.value = eqGains[i]));
    ensureEq();
  };
  equalizer.append(preset);
  const bands = el("div", "eq-bands");
  ["60 Hz", "230 Hz", "910 Hz", "3.6 kHz", "14 kHz"].forEach((label, index) => {
    const slider = el("input");
    slider.type = "range";
    slider.min = -12;
    slider.max = 12;
    slider.step = 0.5;
    slider.value = eqGains[index];
    slider.ariaLabel = label + " gain in decibels";
    slider.oninput = () => {
      eqGains[index] = Number(slider.value);
      ensureEq();
    };
    sliders.push(slider);
    const row = el("label");
    row.append(el("span", "", label), slider);
    bands.append(row);
  });
  equalizer.append(bands, el("small", "", "Manual bands: −12 to +12 dB."));
  const home = group("Home personalization");
  settingRow(
    home,
    "Personalize Home",
    "Uses qualified local listening while keeping Spotify metadata.",
    checkbox(data.personalization, (v) =>
      api("settings", { setting: "personalization", value: v }),
    ),
  );
  const strength = el("input");
  strength.type = "range";
  strength.min = 0;
  strength.max = 100;
  strength.value = data.strength;
  strength.ariaLabel = "Local personalization strength";
  strength.onchange = () =>
    api("settings", {
      setting: "strength",
      value: Number(strength.value),
    }).catch((e) => toast(e.message));
  settingRow(
    home,
    "Local personalization strength",
    "0–100%. Uses the same preference as the phone app.",
    strength,
  );
  const downloads = group("Smart downloads");
  settingRow(
    downloads,
    "Enable Smart Downloads",
    "Off by default. Stores favorite songs on your phone.",
    checkbox(data.smartEnabled, (v) =>
      api("settings", { setting: "smartEnabled", value: v }),
    ),
  );
  const count = el("input", "text-input");
  count.type = "number";
  count.min = 1;
  count.max = 100;
  count.value = data.smartCount;
  count.onchange = () =>
    api("settings", {
      setting: "smartCount",
      value: Math.max(1, Math.min(100, Number(count.value) || 20)),
    }).catch((e) => toast(e.message));
  settingRow(downloads, "Number of songs", "Default 20; up to 100.", count);
  settingRow(
    downloads,
    "Wi-Fi only",
    "Download only while the phone is on Wi-Fi.",
    checkbox(data.smartWifi, (v) =>
      api("settings", { setting: "smartWifi", value: v }),
    ),
  );
  const backup = group("Data & backup");
  backup.append(
    el(
      "p",
      "muted",
      "Back up your local history, radios and settings. Account credentials are excluded. Backups from regular USpot and the Web Preview are compatible.",
    ),
  );
  backup.append(
    button(
      "Export data",
      async () => {
        const res = await fetch("/backup");
        if (!res.ok)
          throw new Error(
            "Backup export failed. Try exporting from your phone.",
          );
        const blob = await res.blob();
        const url = URL.createObjectURL(blob);
        const a = el("a");
        a.href = url;
        a.download = "USpot-backup.zip";
        document.body.append(a);
        a.click();
        a.remove();
        setTimeout(() => URL.revokeObjectURL(url), 60000);
      },
      "primary",
    ),
  );
  const file = el("input");
  file.type = "file";
  file.accept = ".zip,application/zip";
  file.hidden = true;
  file.onchange = () => {
    const selected = file.files?.[0];
    if (!selected) return;
    if (selected.size > 32 * 1024 * 1024) {
      toast("Import backups larger than 32 MB using the phone app.");
      return;
    }
    confirmAction(
      "Import this USpot backup?",
      "This replaces local history, radios and settings on your phone. Export current data first. Your connected account credentials are preserved.",
      async () => {
        audio.pause();
        const res = await fetch("/restore", {
          method: "PUT",
          headers: { "Content-Type": "application/zip" },
          body: selected,
          signal: AbortSignal.timeout(120000),
        });
        const result = await res.json();
        if (!res.ok) throw new Error(result.error || "Import failed");
        state.queue = [];
        state.original = [];
        state.current = null;
        state.collection = null;
        state.index = -1;
        nodes.clear();
        toast("Data imported. Open Home to see your restored music.");
        navigate("home");
        loadLibrary().catch(() => {});
      },
    );
  };
  backup.append(
    button("Import data", () => file.click(), "chip"),
    file,
  );
  const account = group("Account & sources");
  account.append(
    el(
      "p",
      "muted",
      `Using the Spotify account connected on your phone${data.name ? ": " + data.name : ""}. Spotify sign-in, YouTube sign-in, music-source setup, Android permissions and Android Auto remain in the Android app.`,
    ),
  );
  const about = group("About USpot");
  about.append(
    image("/logo.png", "about-logo"),
    el(
      "p",
      "",
      "Built on Spotui. Thank you to the original Spotui creator and contributors.",
    ),
    el(
      "small",
      "",
      `USpot ${data.version || "1.1"}${data.build ? " · build " + data.build : ""} · Desktop & Browser.`,
    ),
  );
}
function renderQueue() {
  const list = $("queue-list");
  list.replaceChildren();
  $("queue-count").textContent =
    `${Math.max(0, state.queue.length - state.index - 1)} songs`;
  $("refresh-radio").hidden = state.collection?.type !== "radio";
  state.queue
    .slice(state.index + 1, state.index + 21)
    .forEach((song, offset) => {
      const at = state.index + 1 + offset;
      const row = el("div", "queue-item");
      row.draggable = true;
      row.dataset.key = song.key;
      row.ondragstart = (e) => {
        e.dataTransfer?.setData("text/plain", String(at));
        row.classList.add("dragging");
      };
      row.ondragend = () => row.classList.remove("dragging");
      row.ondragover = (e) => e.preventDefault();
      row.ondrop = (e) => {
        e.preventDefault();
        const from = Number(e.dataTransfer?.getData("text/plain"));
        if (
          !Number.isInteger(from) ||
          from <= state.index ||
          from >= state.queue.length
        )
          return;
        const [moved] = state.queue.splice(from, 1);
        state.queue.splice(at, 0, moved);
        state.nextReady = null;
        renderQueue();
        persistRadio();
        prepareNext();
      };
      row.append(image(song.image));
      const b = button("", () => {
        state.retries = 0;
        loadTrack(at);
      });
      b.append(
        el("strong", "", song.title),
        el(
          "small",
          "",
          state.manual.has(song.key)
            ? "Added to queue"
            : state.smartKeys.has(song.key)
              ? "◇ Smart Shuffle"
              : song.subtitle,
        ),
      );
      const menu = button("•••", () => songMenu(song));
      menu.ariaLabel = `Options for ${song.title}`;
      const remove = button("×", () => {
        state.queue.splice(at, 1);
        const manual = state.manual.delete(song.key);
        state.nextReady = null;
        renderQueue();
        persistRadio();
        prepareNext();
        undoQueue(song, at, manual);
      });
      remove.ariaLabel = `Remove ${song.title} from queue`;
      row.append(b, menu, remove);
      list.append(row);
    });
}
function undoQueue(song, at, manual) {
  toast("Removed from queue");
  const b = button("Undo", () => {
    state.queue.splice(Math.max(state.index + 1, at), 0, song);
    if (manual) state.manual.add(song.key);
    state.nextReady = null;
    renderQueue();
    persistRadio();
    prepareNext();
    $("toast").hidden = true;
  });
  $("toast").append(b);
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => ($("toast").hidden = true), 8000);
}
$("like-current").onclick = () =>
  state.current && toggleLike(state.current).catch((e) => toast(e.message));
$("current-menu").onclick = () => state.current && songMenu(state.current);
$("refresh-radio").onclick = () =>
  state.collection &&
  tuneRadio(state.collection, null).catch((e) => toast(e.message));
$("theme").onclick = () => {
  localStorage.setItem("uspot-follow-theme", "false");
  localStorage.setItem(
    "uspot-theme",
    document.documentElement.dataset.theme === "light" ? "dark" : "light",
  );
  themePreference();
};
if (typeof matchMedia === "function") {
  matchMedia("(prefers-color-scheme: dark)").addEventListener?.(
    "change",
    themePreference,
  );
  themePreference();
}
audio.addEventListener("playing", () => {
  if (eqEnabled) {
    try {
      ensureEq();
    } catch {
      toast("Equalizer is unavailable in this browser");
    }
  }
});

function fullPlayer() {
  if (!state.current) {
    toast("Choose a song first");
    return;
  }
  const d = dialog("Now playing");
  d.classList.add("full-player");
  const from = el("span", "eyebrow");
  from.id = "full-from";
  const art = image(state.current.image, "full-art");
  art.id = "full-art";
  const title = el("h1");
  title.id = "full-title";
  const artist = el("p", "muted");
  artist.id = "full-artist";
  const actions = el("div", "full-song-actions");
  const like = button(
    "♡ Like",
    () => state.current && toggleLike(state.current),
    "transparent-control",
  );
  like.id = "full-like";
  const radio = button(
    "◉ Radio",
    () => {
      d.close();
      $("start-radio").click();
    },
    "transparent-control",
  );
  radio.id = "full-radio";
  actions.append(
    like,
    radio,
    button(
      "•••",
      () => state.current && songMenu(state.current),
      "transparent-control",
    ),
  );
  const seek = el("input");
  seek.type = "range";
  seek.min = 0;
  seek.max = 1000;
  seek.id = "full-seek";
  seek.ariaLabel = "Seek playback";
  seek.oninput = () => {
    if (Number.isFinite(audio.duration))
      audio.currentTime = (Number(seek.value) / 1000) * audio.duration;
    state.lastTick = 0;
  };
  const clock = el("p", "muted");
  clock.id = "full-clock";
  const controls = el("div", "transport-buttons");
  const shuffle = button("⤨", () => $("shuffle").click(), "quiet");
  shuffle.id = "full-shuffle";
  controls.append(
    shuffle,
    button("❮❮", () => $("previous").click(), "quiet"),
  );
  const play = button("▶", () => $("play").click(), "play");
  play.id = "full-play";
  controls.append(
    play,
    button("❯❯", next, "quiet"),
    button("↻", () => $("repeat").click(), "quiet"),
  );
  d.append(
    from,
    art,
    title,
    artist,
    actions,
    seek,
    clock,
    controls,
    button("Lyrics", () => showLyrics(state.current), "transparent-control"),
  );
  updateFullPlayer();
}
function updateFullPlayer() {
  if (!$("full-title") || !state.current) return;
  const art = $("full-art");
  const trackKey = String(state.current.key || state.current.id || state.current.title);
  const changedTrack = art.dataset.trackKey && art.dataset.trackKey !== trackKey;
  art.dataset.trackKey = trackKey;
  if (changedTrack && art.animate && !window.matchMedia("(prefers-reduced-motion: reduce)").matches) {
    art.getAnimations().forEach((animation) => animation.cancel());
    art.animate([{ opacity: .35, transform: "scale(.985)" }, { opacity: 1, transform: "scale(1)" }], { duration: 320, easing: "cubic-bezier(.2,.8,.2,1)" });
  }
  $("full-title").textContent = state.current.title;
  $("full-artist").textContent = state.current.subtitle;
  $("full-from").textContent =
    `PLAYING FROM ${state.collection?.title || "SEARCH"}`;
  $("full-art").hidden = !state.current.image;
  if (state.current.image) $("full-art").src = state.current.image;
  $("full-play").textContent = audio.paused ? "▶" : "Ⅱ";
  $("full-like").textContent = state.current.liked ? "♥ Liked" : "♡ Like";
  $("full-like").classList.toggle("active", !!state.current.liked);
  $("full-radio").classList.toggle(
    "active",
    state.collection?.type === "radio",
  );
  $("full-shuffle").textContent = state.smart ? "⤨ ◇" : "⤨";
  $("full-shuffle").classList.toggle("active", state.shuffle);
  $("full-clock").textContent =
    `${time(audio.currentTime)} / ${time(audio.duration)}`;
  $("full-seek").value =
    Number.isFinite(audio.duration) && audio.duration > 0
      ? (audio.currentTime / audio.duration) * 1000
      : 0;
}
$("cover").tabIndex = 0;
$("cover").role = "button";
$("cover").ariaLabel = "Open full player";
$("cover").onclick = fullPlayer;
$("cover").onkeydown = (e) => {
  if (e.key === "Enter" || e.key === " ") fullPlayer();
};
$("title").onclick = fullPlayer;
$("title").style.cursor = "pointer";
audio.addEventListener("timeupdate", updateFullPlayer);
audio.addEventListener("playing", updateFullPlayer);
audio.addEventListener("pause", updateFullPlayer);

async function playlistRecommendations(item) {
  const generation = state.renderGeneration;
  try {
    const data = await api("playlistSuggestions");
    if (
      generation !== state.renderGeneration ||
      state.viewCollection?.key !== item.key
    )
      return;
    const group = el("section", "section");
    group.append(
      el("h2", "", "Recommended songs"),
      el(
        "p",
        "muted",
        "Based on your Spotify preferences and qualified USpot listening.",
      ),
    );
    data.songs.forEach((song) => {
      keep(song);
      const row = el("div", "setting-row");
      const name = button(`${song.title} · ${song.subtitle}`, () =>
        playList([song], 0, null),
      );
      const add = button(
        "+ Add",
        async () => {
          add.disabled = true;
          try {
            const result = await api("addToPlaylist", {
              key: song.key,
              playlist: item.key,
            });
            add.textContent = "✓ Added";
            toast(result.warning || "Added to playlist");
          } catch (e) {
            add.disabled = false;
            throw e;
          }
        },
        "transparent-control",
      );
      row.append(name, add);
      group.append(row);
    });
    $("content").append(group);
  } catch (e) {
    toast(e.message);
  }
}
let heartbeat;
async function enter() {
  await api("ping");
  $("pair").hidden = true;
  $("shell").hidden = false;
  $("phone-address").textContent = location.host;
  navigate("home");
  loadLibrary().catch(() => {});
  clearInterval(heartbeat);
  let misses = 0;
  heartbeat = setInterval(async () => {
    try {
      await api("ping");
      misses = 0;
      $("connection-status").textContent = "Connected to " + location.host;
    } catch {
      if (++misses >= 2) {
        audio.pause();
        toast(
          "Phone disconnected. Restart desktop mode on your phone if needed.",
        );
      }
    }
  }, 10000);
}
$("reconnect").onclick = connect;
async function connect() {
  try {
    const res = await fetch("/session");
    if (!res.ok)
      throw new Error("Could not connect. Restart desktop mode on your phone.");
    await enter();
  } catch (e) {
    $("pair").hidden = false;
    $("shell").hidden = true;
    $("pair-error").textContent = e.message;
  }
}
connect();

async function renderDownloadManager(generation) {
  clearTimeout(downloadRefreshTimer);
  const request = ++downloadRequest;
  const data = await api("downloadManager");
  if (
    request !== downloadRequest ||
    state.view !== "downloads" ||
    generation !== state.renderGeneration
  )
    return;
  const content = $("content");
  content.replaceChildren(
    el("span", "eyebrow", "READY WHEN YOU ARE"),
    el("h1", "", "Downloads"),
    el(
      "p",
      "muted",
      `${data.completed} songs on your phone · ${(data.bytes / 1048576).toFixed(1)} MB`,
    ),
  );
  if (data.active.length) {
    content.append(el("h2", "", "Downloading"));
    data.active.forEach((song) => {
      keep(song);
      const row = el("div", "download-task");
      row.append(image(song.image), el("strong", "", song.title));
      const progress = el("progress");
      progress.max = 100;
      progress.value = song.progress;
      progress.ariaLabel = `${song.title}: ${song.progress}%`;
      row.append(
        progress,
        el("span", "", `${song.progress}%`),
        button(
          "Cancel",
          async () => {
            await api("cancelDownload", { key: song.key });
            renderDownloadManager(generation);
          },
          "quiet",
        ),
      );
      content.append(row);
    });
  }
  if (data.failed.length) {
    content.append(
      el("h2", "", "Needs attention"),
      button(
        "Retry all",
        async () => {
          for (const song of data.failed) {
            keep(song);
            await api("retryDownload", { key: song.key });
          }
          renderDownloadManager(generation);
        },
        "chip",
      ),
    );
    data.failed.forEach((song) => {
      keep(song);
      const row = el("div", "download-task");
      row.append(
        image(song.image),
        el("strong", "", song.title),
        el("span", "muted", song.reason),
        button(
          "Retry",
          async () => {
            await api("retryDownload", { key: song.key });
            renderDownloadManager(generation);
          },
          "quiet",
        ),
        button(
          "Dismiss",
          async () => {
            await api("dismissDownload", { key: song.key });
            renderDownloadManager(generation);
          },
          "quiet",
        ),
      );
      content.append(row);
    });
  }
  if (!data.active.length && !data.failed.length)
    content.append(
      el(
        "p",
        "empty",
        "All caught up. Download music from a song or collection menu.",
      ),
    );
  downloadRefreshTimer = setTimeout(() => {
    if (state.view === "downloads" && generation === state.renderGeneration)
      renderDownloadManager(generation).catch(() => {});
  }, 2500);
}
const clearSearch = button(
  "×",
  () => {
    $("search").value = "";
    navigate("search", false);
  },
  "circle",
);
clearSearch.ariaLabel = "Clear search";
document.querySelector(".searchbox").append(clearSearch);
const downloadNav = button("↓ Downloads", () => navigate("downloads"), "nav");
downloadNav.dataset.view = "downloads";
downloadNav.ariaLabel = "Downloads";
downloadNav.replaceChildren(
  el("span", "nav-icon", "↓"),
  el("span", "", "Downloads"),
);
document.querySelector(".sidebar nav").append(downloadNav);

$("more-views").onclick = () => {
  const menu = dialog("Your music");
  [["Your Library", "library"], ["Downloads", "downloads"], ["Local files", "local"], ["Listening history", "history"], ["Your radios", "radios"], ["Settings", "settings"]].forEach(([label, view]) => menu.append(button(label, () => { menu.close(); navigate(view); }, "menu-action")));
};
