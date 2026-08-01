import {
  buildViewDepths,
  buildWindowLayers,
  depthControl,
  evaluateRules,
  filterDepth,
  flattenProperties,
  groupProperties,
  selectNode
} from './inspector-core.js';

const token = new URLSearchParams(location.search).get('token') ?? '';
const elements = Object.fromEntries([
  'device-select', 'scope-select', 'process-select', 'capture-button', 'connection-status',
  'tree-search', 'tree', 'tree-count', 'display-select', 'depth-slider', 'depth-label',
  'rules-toggle', 'empty-state', 'two-d-stage', 'three-d-stage', 'screen-frame', 'screenshot',
  'overlay-layer', 'secure-screenshot', 'scene', 'stage-caption', 'fidelity-badge',
  'selected-title', 'selected-source', 'properties', 'findings', 'finding-count',
  'finding-list', 'diagnostic-list', 'toast', 'three-mode'
].map(id => [id, document.getElementById(id)]));

let state = {
  snapshot: null,
  defaultPackage: null,
  stage: '2d',
  threeMode: 'windows',
  displayId: 0,
  depth: 'all',
  depthLevels: [],
  selectedWindowId: null,
  selectedNodeId: null,
  expanded: new Set(),
  findings: [],
  rotationX: 58,
  rotationZ: -27,
  scale: .9
};

bind();
initialize();

function bind() {
  elements['device-select'].addEventListener('change', loadProcesses);
  elements['scope-select'].addEventListener('change', () => {
    elements['process-select'].disabled = elements['scope-select'].value === 'global';
    renderToolbar();
  });
  elements['capture-button'].addEventListener('click', capture);
  elements['tree-search'].addEventListener('input', renderTree);
  elements['display-select'].addEventListener('change', event => {
    state.displayId = Number(event.target.value);
    chooseDefaultSelection();
    render();
  });
  elements['depth-slider'].addEventListener('input', event => {
    const position = Number(event.target.value);
    state.depth = position === state.depthLevels.length ? 'all' : state.depthLevels[position];
    renderStage();
  });
  elements['rules-toggle'].addEventListener('change', () => {
    state.findings = evaluateRules(state.snapshot, elements['rules-toggle'].checked);
    render();
  });
  document.querySelectorAll('[data-stage]').forEach(button => button.addEventListener('click', () => {
    state.stage = button.dataset.stage;
    renderToolbar();
    renderStage();
  }));
  document.querySelectorAll('[data-three-mode]').forEach(button => button.addEventListener('click', () => {
    state.threeMode = button.dataset.threeMode;
    state.depth = 'all';
    renderToolbar();
    renderStage();
  }));
  bindSceneControls();
}

async function initialize() {
  try {
    const [session, devices] = await Promise.all([api('api/session'), api('api/devices')]);
    if (session.error) throw new Error(session.error);
    state.defaultPackage = session.defaultPackage;
    if (session.snapshot) applySnapshot(session.snapshot);
    fillDevices(Array.isArray(devices) ? devices : []);
  } catch (error) {
    fail(error);
  }
}

async function loadProcesses() {
  const serial = elements['device-select'].value;
  elements['process-select'].replaceChildren(option('', '选择进程'));
  if (!serial) return;
  try {
    const processes = await api(`api/processes?serial=${encodeURIComponent(serial)}`);
    if (processes.error) throw new Error(processes.error);
    for (const process of processes) {
      const item = option(String(process.pid), `${process.processName} · ${process.pid}`);
      item.dataset.package = process.packageName;
      item.dataset.process = process.processName;
      if (state.defaultPackage && (process.packageName === state.defaultPackage || process.processName === state.defaultPackage)) item.selected = true;
      elements['process-select'].append(item);
    }
  } catch (error) {
    fail(error);
  }
}

async function capture() {
  const serial = elements['device-select'].value;
  const scope = elements['scope-select'].value;
  const processOption = elements['process-select'].selectedOptions[0];
  if (!serial) return fail(new Error('请先选择已授权的 Android 设备。'));
  if (scope === 'target' && !processOption?.value) return fail(new Error('请选择目标进程。'));
  setCapturing(true);
  try {
    const snapshot = await api('api/capture', {
      method: 'POST',
      body: JSON.stringify({
        serial,
        scope,
        pid: Number(processOption?.value ?? 0),
        packageName: processOption?.dataset.package ?? null,
        processName: processOption?.dataset.process ?? null
      })
    });
    if (snapshot.error) throw new Error(snapshot.error);
    applySnapshot(snapshot);
    toast('采集完成');
  } catch (error) {
    fail(error);
  } finally {
    setCapturing(false);
  }
}

function applySnapshot(snapshot) {
  state.snapshot = snapshot;
  state.findings = evaluateRules(snapshot, elements['rules-toggle'].checked);
  const displays = [...new Set((snapshot.windows ?? []).map(window => window.displayId))].sort((a, b) => a - b);
  elements['display-select'].replaceChildren(...(displays.length ? displays : [0]).map(value => option(String(value), String(value))));
  state.displayId = displays[0] ?? 0;
  state.expanded = new Set((snapshot.windows ?? []).map(window => window.id));
  chooseDefaultSelection();
  render();
}

function chooseDefaultSelection() {
  if (!state.snapshot) return;
  const windows = buildWindowLayers(state.snapshot, state.displayId);
  const window = windows.at(-1) ?? windows[0];
  state.selectedWindowId = window?.id ?? null;
  state.selectedNodeId = window?.rootNodeId ?? null;
  state.depth = 'all';
}

function render() {
  renderToolbar();
  renderTree();
  renderStage();
  renderProperties();
  renderDiagnostics();
  renderFindings();
  renderStatus();
}

function renderToolbar() {
  document.querySelectorAll('[data-stage]').forEach(button => button.setAttribute('aria-pressed', String(button.dataset.stage === state.stage)));
  document.querySelectorAll('[data-three-mode]').forEach(button => button.setAttribute('aria-pressed', String(button.dataset.threeMode === state.threeMode)));
  elements['three-mode'].hidden = state.stage !== '3d';
  elements['process-select'].disabled = elements['scope-select'].value === 'global';
}

function renderTree() {
  elements.tree.replaceChildren();
  if (!state.snapshot) return;
  const search = elements['tree-search'].value.trim().toLowerCase();
  let visibleCount = 0;
  for (const window of buildWindowLayers(state.snapshot, state.displayId).slice().reverse()) {
    const group = el('section', 'window-group');
    const windowRow = treeRow(windowLabel(window), `L${window.layer}`, state.selectedWindowId === window.id && !state.selectedNodeId, 0, true);
    windowRow.addEventListener('click', () => selectWindow(window.id));
    group.append(windowRow);
    if (state.expanded.has(window.id) && window.rootNodeId) {
      appendNode(group, window.rootNodeId, 1, search, new Set());
    }
    elements.tree.append(group);
    visibleCount++;
  }
  elements['tree-count'].textContent = `${visibleCount} W · ${Object.keys(state.snapshot.nodes ?? {}).length} V`;
}

function appendNode(container, id, indent, search, visited) {
  if (visited.has(id)) return;
  visited.add(id);
  const node = state.snapshot.nodes[id];
  if (!node) return;
  const matches = !search || nodeSearchText(node).includes(search) || descendantsMatch(node, search, new Set());
  if (!matches) return;
  const properties = flattenProperties(node);
  const label = node.className?.split('.').at(-1) ?? node.id;
  const detail = node.resourceId?.split('/').at(-1) ?? node.text ?? '';
  const row = treeRow(`${label}${detail ? ` · ${detail}` : ''}`, `D${node.depth}`, state.selectedNodeId === id, indent, false);
  row.addEventListener('click', () => selectNodeId(id));
  container.append(row);
  for (const child of node.childIds ?? []) appendNode(container, child, indent + 1, search, visited);
}

function treeRow(label, meta, selected, indent, window) {
  const button = el('button', `tree-row${window ? ' window-row' : ''}`);
  button.type = 'button';
  button.setAttribute('role', 'treeitem');
  button.setAttribute('aria-selected', String(selected));
  button.style.paddingLeft = `${5 + indent * 12}px`;
  const caret = el('span', 'tree-caret', window ? '▾' : '·');
  const name = el('span', 'tree-label', label);
  const suffix = el('span', 'tree-meta', meta);
  button.append(caret, name, suffix);
  return button;
}

function renderStage() {
  const hasSnapshot = Boolean(state.snapshot?.windows?.length);
  elements['empty-state'].hidden = hasSnapshot;
  elements['two-d-stage'].hidden = !hasSnapshot || state.stage !== '2d';
  elements['three-d-stage'].hidden = !hasSnapshot || state.stage !== '3d';
  if (!hasSnapshot) return;
  const items = stageItems();
  configureDepth(items);
  if (state.stage === '2d') render2d(); else render3d(items);
  const mode = state.threeMode === 'windows' ? 'Window layer' : 'View depth';
  elements['stage-caption'].textContent = `${mode} · Display ${state.displayId} · ${state.depthLevels.length} 层`;
  const selected = selectedTarget();
  elements['fidelity-badge'].textContent = selected?.source === 'JVMTI' ? 'Real View · JVMTI' : 'Semantic · Accessibility';
}

function stageItems() {
  if (state.threeMode === 'windows') return buildWindowLayers(state.snapshot, state.displayId).map(window => ({ ...window, depth: window.layer }));
  if (!state.selectedWindowId) return [];
  return buildViewDepths(state.snapshot, state.selectedWindowId);
}

function configureDepth(items) {
  const control = depthControl(items, state.depth);
  state.depthLevels = control.levels;
  state.depth = control.selected;
  elements['depth-slider'].max = String(control.maximum);
  elements['depth-slider'].value = String(control.value);
  const noun = state.threeMode === 'windows' ? 'layer' : 'depth';
  elements['depth-label'].textContent = state.depth === 'all' ? `全部 ${noun}` : `${noun} ${state.depth}`;
}

function render2d() {
  const windows = buildWindowLayers(state.snapshot, state.displayId);
  const bounds = unionBounds(windows.map(window => window.bounds));
  const ratio = Math.min(1, 720 / Math.max(bounds.right - bounds.left, 1), 650 / Math.max(bounds.bottom - bounds.top, 1));
  const width = Math.max(160, Math.round((bounds.right - bounds.left) * ratio));
  const height = Math.max(160, Math.round((bounds.bottom - bounds.top) * ratio));
  elements['screen-frame'].style.width = `${width}px`;
  elements['screen-frame'].style.height = `${height}px`;
  const screenshot = state.snapshot.screenshot;
  elements.screenshot.hidden = !screenshot?.dataUrl;
  if (screenshot?.dataUrl) elements.screenshot.src = screenshot.dataUrl;
  elements['secure-screenshot'].hidden = Boolean(screenshot?.dataUrl);
  elements['secure-screenshot'].textContent = screenshot?.unavailableReason ?? '截图不可用；仍可查看公开的 Window 和节点属性。';
  elements['overlay-layer'].replaceChildren();
  const findingIds = new Set(state.findings.map(value => value.nodeId));
  for (const node of Object.values(state.snapshot.nodes ?? {})) {
    const window = state.snapshot.windows.find(value => value.id === node.windowId);
    if (!window || window.displayId !== state.displayId || !node.bounds) continue;
    const box = el('button', `node-box${node.id === state.selectedNodeId ? ' selected' : ''}${findingIds.has(node.id) ? ' finding' : ''}`);
    box.type = 'button';
    box.title = node.className ?? node.id;
    box.style.left = `${(node.bounds.left - bounds.left) * ratio}px`;
    box.style.top = `${(node.bounds.top - bounds.top) * ratio}px`;
    box.style.width = `${Math.max(2, (node.bounds.right - node.bounds.left) * ratio)}px`;
    box.style.height = `${Math.max(2, (node.bounds.bottom - node.bounds.top) * ratio)}px`;
    box.addEventListener('click', event => { event.stopPropagation(); selectNodeId(node.id); });
    elements['overlay-layer'].append(box);
  }
}

function render3d(items) {
  elements.scene.replaceChildren();
  const filtered = filterDepth(items, state.depth);
  const bounds = state.threeMode === 'windows'
    ? unionBounds(items.map(item => item.bounds))
    : state.snapshot.windows.find(window => window.id === state.selectedWindowId)?.bounds ?? unionBounds(items.map(item => item.bounds));
  const width = Math.max(bounds.right - bounds.left, 1);
  const height = Math.max(bounds.bottom - bounds.top, 1);
  const findingIds = new Set(state.findings.map(value => value.nodeId));
  const levels = [...new Set(items.map(item => Number(item.depth ?? item.layer)))].sort((a, b) => a - b);
  for (const item of filtered) {
    const level = Number(item.depth ?? item.layer);
    const z = levels.indexOf(level) * 34;
    const plane = el('button', `layer-plane${state.threeMode === 'windows' ? ' window-plane' : ''}`);
    plane.type = 'button';
    const itemBounds = item.bounds ?? bounds;
    plane.style.left = `${((itemBounds.left - bounds.left) / width) * 100}%`;
    plane.style.top = `${((itemBounds.top - bounds.top) / height) * 100}%`;
    plane.style.width = `${Math.max(4, ((itemBounds.right - itemBounds.left) / width) * 100)}%`;
    plane.style.height = `${Math.max(4, ((itemBounds.bottom - itemBounds.top) / height) * 100)}%`;
    plane.style.transform = `translateZ(${z}px)`;
    const selected = state.threeMode === 'windows' ? item.id === state.selectedWindowId : item.id === state.selectedNodeId;
    if (selected) plane.classList.add('selected');
    if (findingIds.has(item.id)) plane.classList.add('finding');
    plane.append(el('span', 'plane-label', state.threeMode === 'windows' ? windowLabel(item) : item.className?.split('.').at(-1) ?? item.id));
    plane.addEventListener('click', () => state.threeMode === 'windows' ? selectWindow(item.id) : selectNodeId(item.id));
    elements.scene.append(plane);
  }
  updateSceneTransform();
}

function renderProperties() {
  const target = selectedTarget();
  elements.properties.replaceChildren();
  if (!target) {
    elements.properties.append(el('div', 'property-empty', '从左侧树、2D 框或 3D 平面选择一个节点。'));
    return;
  }
  elements['selected-title'].textContent = target.className?.split('.').at(-1) ?? target.title ?? 'Window 属性';
  elements['selected-source'].textContent = target.source === 'JVMTI' ? 'Real View' : 'Semantic';
  for (const group of groupProperties(target)) {
    const details = el('details', 'property-group');
    if (['identity', 'geometryLayout', 'windowHierarchy'].includes(group.name)) details.open = true;
    const entries = Object.entries(group.properties);
    const summary = el('summary');
    summary.append(el('strong', '', groupLabel(group.name)), el('span', '', String(entries.length)));
    details.append(summary);
    const table = el('div', 'property-table');
    if (!entries.length) {
      table.append(el('div', 'property-name', '—'), el('div', 'property-value', '当前后端未提供'));
    } else for (const [name, property] of entries) {
      const key = el('div', 'property-name', name);
      const value = el('div', 'property-value', String(property?.value ?? ''));
      value.append(el('span', 'property-source', property?.source ?? target.source ?? ''));
      table.append(key, value);
    }
    details.append(table);
    elements.properties.append(details);
  }
}

function renderFindings() {
  const enabled = elements['rules-toggle'].checked;
  elements.findings.hidden = !enabled;
  if (!enabled) return;
  elements['finding-count'].textContent = String(state.findings.length);
  elements['finding-list'].replaceChildren();
  if (!state.findings.length) elements['finding-list'].append(el('p', 'property-empty', '当前规则未发现候选。'));
  for (const finding of state.findings) {
    const button = el('button', 'finding-button', `${finding.ruleId} · ${finding.reason}`);
    button.addEventListener('click', () => selectNodeId(finding.nodeId));
    elements['finding-list'].append(button);
  }
}

function renderDiagnostics() {
  elements['diagnostic-list'].replaceChildren();
  const diagnostics = state.snapshot?.diagnostics ?? [];
  if (!diagnostics.length) elements['diagnostic-list'].append(el('p', '', '没有采集警告。'));
  for (const diagnostic of diagnostics) {
    elements['diagnostic-list'].append(el('p', diagnostic.level?.toLowerCase() ?? '', `${diagnostic.code} · ${diagnostic.message}`));
  }
}

function renderStatus() {
  const ready = Boolean(state.snapshot);
  elements['connection-status'].lastChild.textContent = ready ? `已采集 · ${state.snapshot.deviceSerial ?? ''}` : '等待设备';
  elements['connection-status'].querySelector('.status-dot').classList.toggle('ready', ready);
}

function selectWindow(id) {
  state.selectedWindowId = id;
  state.selectedNodeId = null;
  const window = state.snapshot.windows.find(value => value.id === id);
  if (state.threeMode === 'views' && window?.rootNodeId) state.selectedNodeId = window.rootNodeId;
  render();
}

function selectNodeId(id) {
  const node = state.snapshot.nodes[id];
  if (!node) return;
  state = { ...state, ...selectNode(state, id), selectedWindowId: node.windowId };
  render();
}

function selectedTarget() {
  if (!state.snapshot) return null;
  return (state.selectedNodeId && state.snapshot.nodes[state.selectedNodeId])
    || state.snapshot.windows.find(window => window.id === state.selectedWindowId)
    || null;
}

function bindSceneControls() {
  let drag = null;
  elements['three-d-stage'].addEventListener('pointerdown', event => {
    drag = { x: event.clientX, y: event.clientY, rx: state.rotationX, rz: state.rotationZ };
    elements['three-d-stage'].setPointerCapture(event.pointerId);
    elements['three-d-stage'].classList.add('dragging');
  });
  elements['three-d-stage'].addEventListener('pointermove', event => {
    if (!drag) return;
    state.rotationX = Math.max(18, Math.min(78, drag.rx - (event.clientY - drag.y) * .25));
    state.rotationZ = drag.rz + (event.clientX - drag.x) * .25;
    updateSceneTransform();
  });
  elements['three-d-stage'].addEventListener('pointerup', () => {
    drag = null;
    elements['three-d-stage'].classList.remove('dragging');
  });
  elements['three-d-stage'].addEventListener('wheel', event => {
    event.preventDefault();
    state.scale = Math.max(.45, Math.min(1.45, state.scale - Math.sign(event.deltaY) * .06));
    updateSceneTransform();
  }, { passive: false });
}

function updateSceneTransform() {
  elements.scene.style.transform = `rotateX(${state.rotationX}deg) rotateZ(${state.rotationZ}deg) scale(${state.scale})`;
}

async function api(path, options = {}) {
  const separator = path.includes('?') ? '&' : '?';
  const response = await fetch(`${path}${separator}token=${encodeURIComponent(token)}`, {
    headers: { 'Content-Type': 'application/json', ...(options.headers ?? {}) },
    ...options
  });
  if (!response.ok) throw new Error(await response.text() || `HTTP ${response.status}`);
  return response.json();
}

function fillDevices(devices) {
  elements['device-select'].replaceChildren(option('', '选择设备'));
  for (const device of devices) {
    const item = option(device.serial, `${device.model ?? device.serial} · ${device.state}`);
    item.disabled = !device.ready;
    elements['device-select'].append(item);
  }
  const first = devices.find(device => device.ready);
  if (first) {
    elements['device-select'].value = first.serial;
    loadProcesses();
  }
}

function setCapturing(capturing) {
  elements['capture-button'].disabled = capturing;
  elements['capture-button'].textContent = capturing ? '采集中…' : '采集';
}

function fail(error) {
  toast(error.message ?? String(error), true);
  elements['connection-status'].lastChild.textContent = '连接或采集失败';
}

function toast(message, danger = false) {
  elements.toast.textContent = message;
  elements.toast.style.background = danger ? '#8e3d3d' : '';
  elements.toast.hidden = false;
  clearTimeout(toast.timer);
  toast.timer = setTimeout(() => { elements.toast.hidden = true; }, 4200);
}

function descendantsMatch(node, search, visited) {
  if (visited.has(node.id)) return false;
  visited.add(node.id);
  return (node.childIds ?? []).some(id => {
    const child = state.snapshot.nodes[id];
    return child && (nodeSearchText(child).includes(search) || descendantsMatch(child, search, visited));
  });
}

function nodeSearchText(node) {
  return `${node.className ?? ''} ${node.resourceId ?? ''} ${node.text ?? ''} ${node.contentDescription ?? ''}`.toLowerCase();
}

function windowLabel(window) {
  return `${window.title || window.type || 'Window'} · ${window.packageName || 'unknown'}`;
}

function unionBounds(bounds) {
  const valid = bounds.filter(Boolean);
  if (!valid.length) return { left: 0, top: 0, right: 1, bottom: 1 };
  return valid.reduce((result, value) => ({
    left: Math.min(result.left, value.left), top: Math.min(result.top, value.top),
    right: Math.max(result.right, value.right), bottom: Math.max(result.bottom, value.bottom)
  }), { ...valid[0] });
}

function groupLabel(name) {
  return ({
    identity: '标识', windowHierarchy: 'Window 与层级', geometryLayout: '几何与布局',
    renderingTransform: '绘制与变换', content: '内容', interactionState: '交互与状态',
    accessibility: '无障碍', captureDiagnostics: '采集诊断'
  })[name] ?? name;
}

function option(value, label) { const item = document.createElement('option'); item.value = value; item.textContent = label; return item; }
function el(tag, className = '', text = null) { const node = document.createElement(tag); if (className) node.className = className; if (text !== null) node.textContent = text; return node; }
