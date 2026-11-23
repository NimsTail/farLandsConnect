console.log("[farlands-heat] LOADED", new Date().toISOString());

(() => {
  const REGION = 32;
  const CHUNK = 16;
  const MAX_DRAW = 4000;

  const HEAT_MAX = 25000;     // шкала активности (подстрой если нужно)
  const BASE_ALPHA = 0.08;    // общая прозрачность
  const RADIUS_MIN = 500;
  const RADIUS_MAX = 1800;    // было 2500 -> меньше шума/запросов

  // DEBUG включается так:
  // localStorage.setItem("farlandsHeatDebug","1"); location.reload();
  const DEBUG = localStorage.getItem("farlandsHeatDebug") === "1";

  const CACHE = new Map();
  const EMPTY = { cells: [] };

  // фоновая подгрузка регионов: не блокируем отрисовку сети,
  // и не долбим сервер повторными запросами за один и тот же регион
  const PENDING = new Map(); // key -> Promise
  const MAX_CONCURRENT_FETCHES = 6;
  let activeFetches = 0;
  const fetchQueue = [];

  let overlayCanvas = null;
  let ctx = null;

  let lastStatus = "";
  let tick = 0;

  let lastViewKey = "";
  let rafStarted = false;

  function log(...a) {
    if (DEBUG) console.log("[farlands-heat]", ...a);
  }
  function warn(...a) {
    console.warn("[farlands-heat]", ...a);
  }
  function status(msg) {
    if (msg !== lastStatus) {
      lastStatus = msg;
      log("STATUS:", msg);
    }
  }

  function sleep(ms) {
    return new Promise((r) => setTimeout(r, ms));
  }
  function clamp(v, min, max) {
    return Math.max(min, Math.min(max, v));
  }
  function floorDiv(a, b) {
    let r = Math.trunc(a / b);
    if ((a ^ b) < 0 && r * b !== a) r--;
    return r;
  }

  function lerp(a, b, t) {
    return a + (b - a) * t;
  }

  function colorByActivity(activity) {
    const t = clamp(activity / HEAT_MAX, 0, 1);

    // 0..0.2: почти белый -> светло-зелёный
    if (t < 0.2) {
      const k = t / 0.2;
      const r = Math.round(lerp(255, 170, k));
      const g = 255;
      const b = Math.round(lerp(255, 170, k));
      const a = BASE_ALPHA * lerp(0.35, 0.9, k);
      return { r, g, b, a };
    }

    // 0.2..1: зелёный (120°) -> красный (0°)
    const k = (t - 0.2) / 0.8;
    const hue = lerp(120, 0, k);
    const sat = 0.95;
    const val = 0.95;

    const c = val * sat;
    const hp = hue / 60;
    const x = c * (1 - Math.abs((hp % 2) - 1));
    let r1 = 0, g1 = 0, b1 = 0;
    if (hp < 1) [r1, g1, b1] = [c, x, 0];
    else if (hp < 2) [r1, g1, b1] = [x, c, 0];
    else if (hp < 3) [r1, g1, b1] = [0, c, x];
    else if (hp < 4) [r1, g1, b1] = [0, x, c];
    else if (hp < 5) [r1, g1, b1] = [x, 0, c];
    else [r1, g1, b1] = [c, 0, x];

    const m = val - c;
    const r = Math.round((r1 + m) * 255);
    const g = Math.round((g1 + m) * 255);
    const b = Math.round((b1 + m) * 255);

    const a = BASE_ALPHA * (0.55 + 0.75 * Math.pow(t, 0.85));
    return { r, g, b, a };
  }

  function ensureOverlay(dom) {
    if (overlayCanvas) return true;

    const webgl = dom || document.querySelector("canvas");
    const container =
      webgl?.parentElement || document.querySelector("#map") || document.body;

    overlayCanvas = document.createElement("canvas");
    overlayCanvas.style.position = "absolute";
    overlayCanvas.style.left = "0";
    overlayCanvas.style.top = "0";
    overlayCanvas.style.width = "100%";
    overlayCanvas.style.height = "100%";
    overlayCanvas.style.pointerEvents = "none";
    overlayCanvas.style.zIndex = "9999";

    // container должен быть position:relative
    const cs = getComputedStyle(container);
    if (cs.position === "static") container.style.position = "relative";

    container.appendChild(overlayCanvas);
    ctx = overlayCanvas.getContext("2d", { alpha: true });

    if (!ctx) {
      warn("2D context is null (canvas.getContext failed)");
      return false;
    }

    if (DEBUG) {
      overlayCanvas.style.outline = "2px dashed rgba(255,0,0,0.8)";
    }

    const resize = () => {
      const r = container.getBoundingClientRect();
      overlayCanvas.width = Math.max(1, Math.floor(r.width * devicePixelRatio));
      overlayCanvas.height = Math.max(
        1,
        Math.floor(r.height * devicePixelRatio)
      );
      ctx.setTransform(devicePixelRatio, 0, 0, devicePixelRatio, 0, 0);
    };
    resize();
    window.addEventListener("resize", resize);

    log(
      "overlay attached to",
      container,
      "size=",
      overlayCanvas.width,
      overlayCanvas.height
    );
    return true;
  }

  function getWorldFromHash() {
    const h = location.hash || "";
    const m = h.match(/^#([^:]+):/);
    return m ? decodeURIComponent(m[1]) : "world";
  }

  function getThree() {
    const bm = window.bluemap;
    const mv = bm?.mapViewer || bm?.viewer || null;
    if (!mv)
      return {
        bm,
        mv: null,
        camera: null,
        dom: null,
        controls: null,
        THREE: null,
      };

    const camera =
      mv.camera ||
      mv._camera ||
      mv.scene?.camera ||
      mv.map?.camera ||
      bm?.mapViewer?.camera ||
      null;

    const controls =
      mv.controls ||
      mv._controls ||
      mv.controller ||
      mv.mapControls ||
      bm?.mapViewer?.controls ||
      null;

    const dom =
      mv.renderer?.domElement ||
      mv.webglRenderer?.domElement ||
      document.querySelector("canvas");

    // Самое важное: THREE может быть НЕ в window.THREE
    const THREE =
      window.THREE || mv.THREE || bm?.THREE || mv?.scene?.THREE || null;

    return { bm, mv, camera, dom, controls, THREE };
  }

  function getTargetFromControls(controls) {
    if (!controls) return null;

    const t =
      controls.target ||
      controls._target ||
      controls.getTarget?.() ||
      controls.getFocus?.() ||
      null;

    if (t && typeof t.x === "number" && typeof t.z === "number") return t;
    return null;
  }

  function getTargetFromCamera(camera) {
    if (!camera) return null;

    camEnsureUpdated(camera);

    const origin = camPos(camera);
    const dir = camForward(camera);
    if (!origin || !dir) return null;

    return rayToPlaneY(origin, dir, 120);
  }

  function getDistanceFromControls(controls) {
    if (!controls) return null;
    try {
      if (typeof controls.getDistance === "function")
        return controls.getDistance();
    } catch {}
    return null;
  }

  function getDistanceFromCamera(camera, target){
    if (!camera || !target) return 2000;

    camEnsureUpdated(camera);

    const p = camPos(camera);
    if (!p) return 2000;

    const dx = p.x - target.x;
    const dy = p.y - (target.y ?? 120);
    const dz = p.z - target.z;

    const d = Math.sqrt(dx*dx + dy*dy + dz*dz);
    return Number.isFinite(d) ? d : 2000;
  }

  function runQueuedFetch(task) {
    return new Promise((resolve) => {
      fetchQueue.push({ task, resolve });
      pumpFetchQueue();
    });
  }

  function pumpFetchQueue() {
    while (activeFetches < MAX_CONCURRENT_FETCHES && fetchQueue.length) {
      const { task, resolve } = fetchQueue.shift();
      activeFetches++;
      task()
        .then(resolve)
        .finally(() => {
          activeFetches--;
          pumpFetchQueue();
        });
    }
  }

  async function doFetchRegion(world, rx, rz, url) {
    try {
      const res = await fetch(url, { cache: "no-cache" });
      if (res.status === 404) return EMPTY;
      if (!res.ok) {
        log("fetch bad status", res.status, url);
        return EMPTY;
      }
      const data = await res.json();
      return data && Array.isArray(data.cells) ? data : EMPTY;
    } catch (e) {
      log("fetch error", url, e);
      return EMPTY;
    }
  }

  // Возвращает данные региона немедленно, если они уже в кэше (в т.ч. EMPTY для
  // ещё не сгенерированных регионов). Если данных ещё нет — не блокирует
  // отрисовку: ставит запрос в очередь (с дедупликацией) и, когда он придёт,
  // просит перерисовать вид.
  function getRegionCachedOrQueue(world, rx, rz, onArrive) {
    const key = `${world}:${rx}:${rz}`;
    if (CACHE.has(key)) return CACHE.get(key);

    if (!PENDING.has(key)) {
      const url = `/farlands-heat/${encodeURIComponent(
        world
      )}/r.${rx}.${rz}.json`;

      const p = runQueuedFetch(() => doFetchRegion(world, rx, rz, url)).then(
        (data) => {
          CACHE.set(key, data);
          PENDING.delete(key);
          onArrive();
          return data;
        }
      );
      PENDING.set(key, p);
    }

    return EMPTY;
  }

  function projectToScreen(x, y, z, camera, THREE) {
    // если вдруг THREE есть — можно использовать старый путь, но он не обязателен
    if (THREE && overlayCanvas && camera) {
      try {
        const v = new THREE.Vector3(x, y, z);
        v.project(camera);
        const W = overlayCanvas.width / devicePixelRatio;
        const H = overlayCanvas.height / devicePixelRatio;
        return { x: (v.x * 0.5 + 0.5) * W, y: (-v.y * 0.5 + 0.5) * H, z: v.z };
      } catch {}
    }
    return projectToScreenNoTHREE(x, y, z, camera);
  }

  function update() {
    tick++;

    const { mv, camera, controls, dom, THREE } = getThree();

    if (!ensureOverlay(dom)) {
      status("no 2D ctx / overlay failed");
      return;
    }

    if (!mv) {
      status("no mapViewer (window.bluemap not ready?)");
      return;
    }
    if (!camera) {
      status("no camera found");
      return;
    }
    // if (!THREE) { status("no THREE found (not in window.THREE/mv.THREE)"); return; }

    // 1) пробуем controls, если он всё-таки появится в будущем
    let target = getTargetFromControls(controls);
    let dist = getDistanceFromControls(controls);

    // 2) fallback: вычисляем target из камеры
    if (!target) {
      target = getTargetFromCamera(camera);
      if (!target) {
        status("no controls target; camera fallback failed");
        return;
      }
    }

    // 3) fallback для distance
    if (dist == null) {
      dist = getDistanceFromCamera(camera, target);
    }

    if (!controls) {
      status("OK (no controls) using camera-ray target");
    }

    const world = getWorldFromHash();

    const radiusBlocks = clamp(dist * 0.35, RADIUS_MIN, RADIUS_MAX);

    const minX = target.x - radiusBlocks;
    const maxX = target.x + radiusBlocks;
    const minZ = target.z - radiusBlocks;
    const maxZ = target.z + radiusBlocks;

    const cMinX = floorDiv(Math.floor(minX), CHUNK);
    const cMaxX = floorDiv(Math.floor(maxX), CHUNK);
    const cMinZ = floorDiv(Math.floor(minZ), CHUNK);
    const cMaxZ = floorDiv(Math.floor(maxZ), CHUNK);

    const rMinX = floorDiv(cMinX, REGION);
    const rMaxX = floorDiv(cMaxX, REGION);
    const rMinZ = floorDiv(cMinZ, REGION);
    const rMaxZ = floorDiv(cMaxZ, REGION);

    // очищаем canvas
    ctx.clearRect(
      0,
      0,
      overlayCanvas.width / devicePixelRatio,
      overlayCanvas.height / devicePixelRatio
    );

    // визуальные настройки (меньше "грязи")
    ctx.imageSmoothingEnabled = true;
    ctx.globalCompositeOperation = "source-over";

    // DEBUG: метка центра, и иногда раз в ~2 сек печатаем состояние
    if (DEBUG) {
      const W = overlayCanvas.width / devicePixelRatio;
      const H = overlayCanvas.height / devicePixelRatio;
      ctx.fillStyle = "rgba(255,0,0,0.35)";
      ctx.fillRect(W / 2 - 8, H / 2 - 8, 16, 16);

      if (tick % 8 === 0) {
        log(
          "tick",
          tick,
          "world=",
          world,
          "target=",
          { x: target.x, y: target.y, z: target.z },
          "dist=",
          Math.round(dist),
          "radius=",
          Math.round(radiusBlocks),
          "regions=",
          [rMinX, rMaxX, rMinZ, rMaxZ],
          "chunks=",
          [cMinX, cMaxX, cMinZ, cMaxZ]
        );
      }
    }

    // берём регионы из кэша сразу; недостающие догружаем в фоне и
    // перерисовываем, когда они придут (не блокируем текущий кадр)
    const datas = [];
    for (let rx = rMinX; rx <= rMaxX; rx++) {
      for (let rz = rMinZ; rz <= rMaxZ; rz++) {
        datas.push(
          getRegionCachedOrQueue(world, rx, rz, () => {
            // регион догрузился - если вид не успел уйти дальше, перерисуем
            update();
          })
        );
      }
    }

    let drawn = 0;
    let seenCells = 0;

    // рисуем чанки как projected rect
    for (const data of datas) {
      const cells = data?.cells;
      if (!Array.isArray(cells) || !cells.length) continue;

      for (const cell of cells) {
        seenCells++;

        const cx = cell.cx,
          cz = cell.cz;
        if (cx < cMinX || cx > cMaxX || cz < cMinZ || cz > cMaxZ) continue;

        const a = cell.a || 0;
        const t = cell.t || 0;
        if (a === 0 && t === 0) continue;

        const x0 = cx * CHUNK;
        const z0 = cz * CHUNK;
        const x1 = x0 + CHUNK;
        const z1 = z0 + CHUNK;

        // Важно: y берём ближе к земле, а не target.y (который может быть странным)
        const y = 120;

        const p00 = projectToScreen(x0, y, z0, camera, THREE);
        const p10 = projectToScreen(x1, y, z0, camera, THREE);
        const p11 = projectToScreen(x1, y, z1, camera, THREE);
        const p01 = projectToScreen(x0, y, z1, camera, THREE);

        if (!p00 || !p10 || !p11 || !p01) continue;

        const col = colorByActivity(a);
        ctx.fillStyle = `rgba(${col.r},${col.g},${col.b},${col.a})`;

        ctx.beginPath();
        ctx.moveTo(p00.x, p00.y);
        ctx.lineTo(p10.x, p10.y);
        ctx.lineTo(p11.x, p11.y);
        ctx.lineTo(p01.x, p01.y);
        ctx.closePath();
        ctx.fill();

        drawn++;
        if (drawn >= MAX_DRAW) break;
      }

      if (drawn >= MAX_DRAW) break;
    }

    status(`OK drawn=${drawn} seenCells=${seenCells} cache=${CACHE.size}`);
    if (DEBUG && tick % 8 === 0) {
      log("draw stats:", { drawn, seenCells, cache: CACHE.size });
    }
  }

  function dumpControlsCandidates() {
    const bm = window.bluemap;
    const mv = bm?.mapViewer || bm?.viewer;
    const out = {};

    try {
      out.bluemap_keys = bm ? Object.keys(bm) : null;
      out.mapViewer_keys = mv ? Object.keys(mv) : null;

      // Популярные места, где прячут controls в разных билдах
      const candidates = {
        "mv.controls": mv?.controls,
        "mv._controls": mv?._controls,
        "mv.controller": mv?.controller,
        "mv._controller": mv?._controller,
        "mv.mapControls": mv?.mapControls,
        "mv._mapControls": mv?._mapControls,
        "mv.scene.controls": mv?.scene?.controls,
        "mv.scene._controls": mv?.scene?._controls,
        "mv.map.controls": mv?.map?.controls,
        "mv.map._controls": mv?.map?._controls,
        "bm.mapViewer.controls": bm?.mapViewer?.controls,
        "bm.mapViewer._controls": bm?.mapViewer?._controls,
        "bm.controls": bm?.controls,
        "bm.viewer.controls": bm?.viewer?.controls,
      };

      const hit = [];
      for (const [k, v] of Object.entries(candidates)) {
        if (!v) continue;
        const keys = (() => {
          try {
            return Object.keys(v);
          } catch {
            return null;
          }
        })();
        hit.push({ key: k, type: typeof v, ctor: v?.constructor?.name, keys });
      }

      out.controls_hits = hit;

      console.log("[farlands-heat] CONTROLS DUMP", out);
    } catch (e) {
      console.warn("[farlands-heat] CONTROLS DUMP ERROR", e);
    }
  }

  function camEnsureUpdated(camera) {
    try {
      camera.updateMatrixWorld?.(true);
    } catch {}
    try {
      camera.updateProjectionMatrix?.();
    } catch {}
  }

  function camPos(camera) {
    // matrixWorld is column-major elements
    const e = camera?.matrixWorld?.elements;
    if (!e) return null;
    return { x: e[12], y: e[13], z: e[14] };
  }

  function camForward(camera) {
    // Forward in Three.js world space is -Z axis of matrixWorld basis
    const e = camera?.matrixWorld?.elements;
    if (!e) return null;
    const x = -e[8],
      y = -e[9],
      z = -e[10];
    const len = Math.hypot(x, y, z) || 1;
    return { x: x / len, y: y / len, z: z / len };
  }

  function rayToPlaneY(origin, dir, planeY) {
    const denom = dir.y;
    if (Math.abs(denom) < 1e-6) return null;
    const t = (planeY - origin.y) / denom;
    if (!Number.isFinite(t) || t <= 0) return null;
    return {
      x: origin.x + dir.x * t,
      y: origin.y + dir.y * t,
      z: origin.z + dir.z * t,
    };
  }

  function mat4MulVec4(m, v) {
    // m is length-16 column-major (Three.js style), v is [x,y,z,w]
    const x = v[0],
      y = v[1],
      z = v[2],
      w = v[3];
    return [
      m[0] * x + m[4] * y + m[8] * z + m[12] * w,
      m[1] * x + m[5] * y + m[9] * z + m[13] * w,
      m[2] * x + m[6] * y + m[10] * z + m[14] * w,
      m[3] * x + m[7] * y + m[11] * z + m[15] * w,
    ];
  }

  function mat4MulMat4(a, b) {
    // column-major: out = a*b
    const out = new Array(16).fill(0);
    for (let col = 0; col < 4; col++) {
      for (let row = 0; row < 4; row++) {
        out[col * 4 + row] =
          a[0 * 4 + row] * b[col * 4 + 0] +
          a[1 * 4 + row] * b[col * 4 + 1] +
          a[2 * 4 + row] * b[col * 4 + 2] +
          a[3 * 4 + row] * b[col * 4 + 3];
      }
    }
    return out;
  }

  function projectToScreenNoTHREE(x, y, z, camera) {
    if (!overlayCanvas || !camera) return null;

    camEnsureUpdated(camera);

    const proj = camera.projectionMatrix?.elements;
    const view = camera.matrixWorldInverse?.elements;
    if (!proj || !view) return null;

    const vp = mat4MulMat4(proj, view);
    const clip = mat4MulVec4(vp, [x, y, z, 1]);

    const w = clip[3];
    if (!Number.isFinite(w) || Math.abs(w) < 1e-6) return null;

    const ndcX = clip[0] / w;
    const ndcY = clip[1] / w;
    const ndcZ = clip[2] / w;

    if (
      !Number.isFinite(ndcX) ||
      !Number.isFinite(ndcY) ||
      !Number.isFinite(ndcZ)
    )
      return null;

    const W = overlayCanvas.width / devicePixelRatio;
    const H = overlayCanvas.height / devicePixelRatio;

    const sx = (ndcX * 0.5 + 0.5) * W;
    const sy = (-ndcY * 0.5 + 0.5) * H;

    return { x: sx, y: sy, z: ndcZ };
  }
  
    function viewKey(camera) {
    if (!overlayCanvas || !camera) return "";
    camEnsureUpdated(camera);

    const p = camPos(camera);
    const e = camera?.matrixWorld?.elements;
    if (!p || !e) return "";

    // округляем, чтобы микродрожь не триггерила перерисовку
    return [
      p.x.toFixed(2), p.y.toFixed(2), p.z.toFixed(2),
      e[0].toFixed(4), e[1].toFixed(4), e[2].toFixed(4),
      e[8].toFixed(4), e[9].toFixed(4), e[10].toFixed(4),
      overlayCanvas.width, overlayCanvas.height
    ].join("|");
  }

  async function main() {
    log("debug is", DEBUG ? "ON" : "OFF");

    while (!window.bluemap) {
      status("waiting window.bluemap...");
      await sleep(200);
    }

    dumpControlsCandidates();

    const { dom } = getThree();
    ensureOverlay(dom);

    try {
      update();
    } catch (e) {
      warn("update error:", e);
    }

    if (!rafStarted) {
      rafStarted = true;

      const loop = () => {
        try {
          const { camera } = getThree();
          const k = viewKey(camera);
          if (k && k !== lastViewKey) {
            lastViewKey = k;
            update();
          }
        } catch (e) {
          warn("loop error:", e);
        }
        requestAnimationFrame(loop);
      };

      requestAnimationFrame(loop);
    }

  }

  main();
})();
