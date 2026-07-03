// LifeOps PWA service worker.
// The dashboard is live/dynamic (status, history, gym stats change constantly),
// so this is network-first everywhere -- it exists to make "Add to Home
// Screen" installable and give instant reloads when the network hiccups, not
// to serve stale data. Falls back to the last cached response only on
// network failure (e.g. Tailscale briefly unreachable).
const CACHE = "lifeops-shell-v1";
const SHELL = ["/", "/static/manifest.json", "/static/icons/icon-192.png"];

self.addEventListener("install", (event) => {
  event.waitUntil(caches.open(CACHE).then((c) => c.addAll(SHELL)));
  self.skipWaiting();
});

self.addEventListener("activate", (event) => {
  event.waitUntil(
    caches.keys().then((keys) =>
      Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k)))
    )
  );
  self.clients.claim();
});

self.addEventListener("fetch", (event) => {
  if (event.request.method !== "GET") return;
  event.respondWith(
    fetch(event.request)
      .then((res) => {
        const copy = res.clone();
        caches.open(CACHE).then((c) => c.put(event.request, copy));
        return res;
      })
      .catch(() => caches.match(event.request))
  );
});
