import {
  auditAccessibility,
  buildViewDepths,
  buildWindowLayers,
  buildWindowScene,
  depthControl,
  dragRotation,
  evaluateRules,
  filterDepth,
  flattenProperties,
  groupProperties,
  initialExpandedSet,
  isDragGesture,
  mutationKey,
  outsideViewport,
  pickNodeAt,
  preferredProjectProcess,
  previewPoint,
  propertyEditorModel,
  propertyGroupOpen,
  propertyPanelScrollTop,
  relativeBounds,
  rememberOriginalValue,
  rememberPropertyGroupOpen,
  selectNode,
  screenshotSlice,
  shouldAutoCapture,
  toggleExpanded,
  windowViewZIndex
} from './inspector-core.js';

const token = new URLSearchParams(location.search).get('token') ?? '';
const elements = Object.fromEntries([
  'device-select', 'scope-select', 'process-select', 'capture-button', 'connection-status',
  'tree-search', 'tree', 'tree-count', 'display-select', 'depth-slider', 'depth-label',
  'rules-toggle', 'empty-state', 'two-d-stage', 'three-d-stage', 'screen-frame', 'screenshot',
  'overlay-layer', 'secure-screenshot', 'scene', 'stage-caption', 'fidelity-badge',
  'property-panel', 'selected-title', 'selected-source', 'properties', 'findings', 'finding-count',
  'finding-list', 'accessibility-audit', 'accessibility-audit-count', 'accessibility-audit-list',
  'diagnostic-list', 'toast', 'three-mode'
].map(id => [id, document.getElementById(id)]));

let state = {
  snapshot: null,
  projectPackages: [],
  autoCaptureAttempted: false,
  stage: '2d',
  threeMode: 'windows',
  displayId: 0,
  depth: 'all',
  depthLevels: [],
  selectedWindowId: null,
  selectedNodeId: null,
  expanded: new Set(),
  findings: [],
  accessibilityAudit: { enabled: [], disabled: [], issues: [] },
  twoD: { zoom: 1, panX: 0, panY: 0 },
  treeWidth: 306,
  accessibilityFilter: 'issues',
  editingProperty: null,
  mutationBusy: false,
  originalValues: new Map(),
  propertyGroupState: new Map(),
  renderedPropertyTargetId: null,
  rotationX: 58,
  rotationZ: -27,
  scale: .9
};

bind();
initialize();

function bind() {
  elements['device-select'].addEventListener('change', () => {
    clearMutationState();
    loadProcesses();
  });
  elements['scope-select'].addEventListener('change', () => {
    clearMutationState();
    elements['process-select'].disabled = elements['scope-select'].value === 'global';
    renderToolbar();
  });
  elements['process-select'].addEventListener('change', clearMutationState);
  elements['capture-button'].addEventListener('click', capture);
  elements['tree-search'].addEventListener('input', renderTree);
  elements['screen-frame'].addEventListener('click', event => {
    if (!state.snapshot || state.stage !== '2d') return;
    const windows = buildWindowLayers(state.snapshot, state.displayId);
    const bounds = unionBounds(windows.map(window => window.bounds));
    const point = previewPoint(
      elements['screen-frame'].getBoundingClientRect(),
      bounds,
      event.clientX,
      event.clientY
    );
    const node = point && pickNodeAt(state.snapshot, state.displayId, point.x, point.y);
    if (node) selectNodeId(node.id, true);
  });
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
  document.querySelectorAll('[data-accessibility-filter]').forEach(button => button.addEventListener('click', () => {
    state.accessibilityFilter = button.dataset.accessibilityFilter;
    renderAccessibilityAudit();
  }));
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
  bindTwoDPanZoom();
  bindTreeResize();
}

function bindTreeResize() {
  const handle = document.getElementById('tree-resize-handle');
  let drag = null;

  handle.addEventListener('pointerdown', event => {
    drag = { x: event.clientX, pointerId: event.pointerId };
    handle.classList.add('active');
    handle.setPointerCapture(event.pointerId);
    event.preventDefault();
  });
  handle.addEventListener('pointermove', event => {
    if (!drag || event.pointerId !== drag.pointerId) return;
    const delta = event.clientX - drag.x;
    drag.x = event.clientX;
    const current = parseFloat(getComputedStyle(document.querySelector('.workspace')).gridTemplateColumns.split(' ')[0]) || 306;
    const next = Math.max(180, Math.min(600, current + delta));
    state.treeWidth = next;
    document.querySelector('.workspace').style.setProperty('--tree-width', `${next}px`);
  });
  const clear = () => {
    drag = null;
    handle.classList.remove('active');
  };
  handle.addEventListener('pointerup', clear);
  handle.addEventListener('pointercancel', clear);
  handle.addEventListener('lostpointercapture', clear);
}

function bindTwoDPanZoom() {
  const stage = elements['two-d-stage'];
  let drag = null;

  stage.addEventListener('wheel', event => {
    if (!state.snapshot || state.stage !== '2d') return;
    event.preventDefault();
    const factor = 1 - Math.sign(event.deltaY) * 0.12;
    const newZoom = Math.max(0.15, Math.min(6, state.twoD.zoom * factor));
    // Zoom toward cursor position
    const rect = elements['screen-frame'].getBoundingClientRect();
    const cx = event.clientX - rect.left;
    const cy = event.clientY - rect.top;
    const ratio = newZoom / state.twoD.zoom;
    state.twoD.panX = event.clientX - cx * ratio - (rect.left - state.twoD.panX) * ratio;
    state.twoD.panY = event.clientY - cy * ratio - (rect.top - state.twoD.panY) * ratio;
    state.twoD.zoom = newZoom;
    applyTwoDTransform();
  }, { passive: false });

  const clearDrag = () => {
    drag = null;
    stage.classList.remove('two-d-dragging');
  };
  const suppressGeneratedClick = () => {
    const suppress = event => {
      event.preventDefault();
      event.stopImmediatePropagation();
    };
    elements['screen-frame'].addEventListener('click', suppress, { capture: true, once: true });
    setTimeout(() => elements['screen-frame'].removeEventListener('click', suppress, true), 0);
  };

  stage.addEventListener('pointerdown', event => {
    if (!state.snapshot || state.stage !== '2d') return;
    drag = { x: event.clientX, y: event.clientY, px: state.twoD.panX, py: state.twoD.panY, pointerId: event.pointerId, active: false };
  });
  stage.addEventListener('pointermove', event => {
    if (!drag || event.pointerId !== drag.pointerId) return;
    if (!drag.active) {
      if (!isDragGesture(drag.x, drag.y, event.clientX, event.clientY)) return;
      drag.active = true;
      stage.setPointerCapture(event.pointerId);
      stage.classList.add('two-d-dragging');
    }
    state.twoD.panX = drag.px + (event.clientX - drag.x);
    state.twoD.panY = drag.py + (event.clientY - drag.y);
    applyTwoDTransform();
  });
  stage.addEventListener('pointerup', event => {
    if (!drag || event.pointerId !== drag.pointerId) return;
    if (drag.active) suppressGeneratedClick();
    clearDrag();
  });
  stage.addEventListener('pointercancel', clearDrag);
  stage.addEventListener('lostpointercapture', clearDrag);
}

function applyTwoDTransform() {
  elements['screen-frame'].style.transform =
    `translate(${state.twoD.panX}px, ${state.twoD.panY}px) scale(${state.twoD.zoom})`;
}

async function initialize() {
  try {
    const [session, devices] = await Promise.all([api('api/session'), api('api/devices')]);
    if (session.error) throw new Error(session.error);
    state.projectPackages = Array.isArray(session.projectPackages)
      ? session.projectPackages
      : session.defaultPackage ? [session.defaultPackage] : [];
    if (session.snapshot) applySnapshot(session.snapshot);
    await fillDevices(Array.isArray(devices) ? devices : [], true);
  } catch (error) {
    fail(error);
  }
}

async function loadProcesses(autoCapture = false) {
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
      elements['process-select'].append(item);
    }
    const preferred = preferredProjectProcess(processes, state.projectPackages);
    if (preferred) elements['process-select'].value = String(preferred.pid);
    if (autoCapture && shouldAutoCapture({ attempted: state.autoCaptureAttempted, process: preferred })) {
      state.autoCaptureAttempted = true;
      await capture();
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
  clearMutationState();
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
    const hasData = (snapshot.windows ?? []).length > 0 || Object.keys(snapshot.nodes ?? {}).length > 0;
    if (!hasData) {
      const accessibilityError = (snapshot.diagnostics ?? []).find(
        d => d.code === 'accessibility_required' || d.code === 'accessibility_disabled');
      if (accessibilityError) {
        fail(new Error(accessibilityError.message || '请在设备设置中开启 UI Inspector Collector 无障碍服务后重试。'));
        return;
      }
      toast('采集完成，但未获取到任何 Window 或 View 节点。请检查无障碍服务是否已开启。', true);
      return;
    }
    toast('采集完成');
  } catch (error) {
    fail(error);
  } finally {
    setCapturing(false);
  }
}

function applySnapshot(snapshot) {
  state.snapshot = snapshot;
  state.twoD = { zoom: 1, panX: 0, panY: 0 };
  state.findings = evaluateRules(snapshot, elements['rules-toggle'].checked);
  updateAccessibilityAudit(snapshot, true);
  const displays = [...new Set((snapshot.windows ?? []).map(window => window.displayId))].sort((a, b) => a - b);
  elements['display-select'].replaceChildren(...(displays.length ? displays : [0]).map(value => option(String(value), String(value))));
  state.displayId = displays[0] ?? 0;
  state.expanded = initialExpandedSet(snapshot);
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
  renderAccessibilityAudit();
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
  const fragment = document.createDocumentFragment();
  for (const window of buildWindowLayers(state.snapshot, state.displayId).slice().reverse()) {
    const group = el('section', 'window-group');
    const expandable = Boolean(window.rootNodeId);
    const expanded = Boolean(search) || state.expanded.has(window.id);
    const windowRow = treeRow(windowLabel(window), `L${window.layer}`, state.selectedWindowId === window.id && !state.selectedNodeId, 0, true, expandable, expanded);
    windowRow.dataset.windowId = window.id;
    windowRow.addEventListener('click', event => onTreeRowClick(event, () => selectWindow(window.id), window.id, expandable));
    group.append(windowRow);
    if (expanded && window.rootNodeId) {
      appendNode(group, window.rootNodeId, 1, search, new Set());
    }
    fragment.append(group);
    visibleCount++;
  }
  elements.tree.append(fragment);
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
  const expandable = (node.childIds?.length ?? 0) > 0;
  const expanded = Boolean(search) || state.expanded.has(id);
  const label = node.className?.split('.').at(-1) ?? node.id;
  const detail = node.resourceId?.split('/').at(-1) ?? node.text ?? '';
  const row = treeRow(`${label}${detail ? ` · ${detail}` : ''}`, `D${node.depth}`, state.selectedNodeId === id, indent, false, expandable, expanded);
  row.title = `${node.className ?? ''}\n${node.resourceId ?? ''}\n${node.id}`;
  row.dataset.nodeId = id;
  row.addEventListener('click', event => onTreeRowClick(event, () => selectNodeId(id), id, expandable));
  container.append(row);
  if (expanded) {
    for (const child of node.childIds ?? []) appendNode(container, child, indent + 1, search, visited);
  }
}

function treeRow(label, meta, selected, indent, window, expandable, expanded) {
  const button = el('button', `tree-row${window ? ' window-row' : ''}`);
  button.type = 'button';
  button.setAttribute('role', 'treeitem');
  button.setAttribute('aria-selected', String(selected));
  if (expandable) button.setAttribute('aria-expanded', String(expanded));
  button.style.paddingLeft = `${5 + indent * 12}px`;
  const caretGlyph = !expandable ? '·' : expanded ? '▾' : '▸';
  const caret = el('span', 'tree-caret', caretGlyph);
  if (expandable) caret.classList.add('expandable');
  const name = el('span', 'tree-label', label);
  const suffix = el('span', 'tree-meta', meta);
  button.append(caret, name, suffix);
  return button;
}

function onTreeRowClick(event, select, id, expandable) {
  if (expandable && event.target.closest('.tree-caret')) {
    state.expanded = toggleExpanded(state.expanded, id);
    renderTree();
    return;
  }
  select();
}

function renderStage() {
  const hasSnapshot = Boolean(state.snapshot?.windows?.length);
  elements['empty-state'].hidden = hasSnapshot;
  elements['two-d-stage'].hidden = !hasSnapshot || state.stage !== '2d';
  elements['three-d-stage'].hidden = !hasSnapshot || state.stage !== '3d';
  if (!hasSnapshot) {
    // 已采集过但数据为空，更新提示文案
    if (state.snapshot) {
      const emptyHeading = elements['empty-state'].querySelector('h2');
      const emptyDesc = elements['empty-state'].querySelector('p');
      if (emptyHeading) emptyHeading.textContent = '未获取到 Window 或 View';
      if (emptyDesc) emptyDesc.textContent = '请确认设备上已开启 UI Inspector Collector 无障碍服务，然后重新采集。';
    }
    return;
  }
  const items = stageItems();
  configureDepth(items);
  if (state.stage === '2d') render2d(); else render3d(items);
  const mode = state.threeMode === 'windows' ? 'Window layer' : 'View depth';
  elements['stage-caption'].textContent = `${mode} · Display ${state.displayId} · ${state.depthLevels.length} 层`;
  const selected = selectedTarget();
  elements['fidelity-badge'].textContent = selected?.source === 'JVMTI' ? 'Real View · JVMTI' : 'Semantic · Accessibility';
}

function stageItems() {
  if (state.threeMode === 'windows') return buildWindowScene(state.snapshot, state.displayId)
    .map(window => ({ ...window, depth: window.layer }));
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
  const fragment = document.createDocumentFragment();
  for (const node of Object.values(state.snapshot.nodes ?? {})) {
    const window = state.snapshot.windows.find(value => value.id === node.windowId);
    if (!window || window.displayId !== state.displayId || !node.bounds) continue;
    const box = el('div', `node-box${node.id === state.selectedNodeId ? ' selected' : ''}${findingIds.has(node.id) ? ' finding' : ''}`);
    box.setAttribute('aria-hidden', 'true');
    box.dataset.nodeId = node.id;
    box.title = node.className ?? node.id;
    box.style.left = `${(node.bounds.left - bounds.left) * ratio}px`;
    box.style.top = `${(node.bounds.top - bounds.top) * ratio}px`;
    box.style.width = `${Math.max(2, (node.bounds.right - node.bounds.left) * ratio)}px`;
    box.style.height = `${Math.max(2, (node.bounds.bottom - node.bounds.top) * ratio)}px`;
    fragment.append(box);
  }
  elements['overlay-layer'].append(fragment);
  applyTwoDTransform();
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
  const depthSequence = new Map();
  for (const item of filtered) {
    const level = Number(item.depth ?? item.layer);
    const offset = depthSequence.get(level) ?? 0;
    depthSequence.set(level, offset + 1);
    const z = levels.indexOf(level) * 34 + offset * 2;
    const windowMode = state.threeMode === 'windows';
    const plane = el(windowMode ? 'div' : 'button', `layer-plane${windowMode ? ' window-plane' : ''}`);
    if (windowMode) {
      plane.tabIndex = 0;
      plane.setAttribute('role', 'button');
    } else {
      plane.type = 'button';
      plane.dataset.nodeId = item.id;
    }
    const itemBounds = item.bounds ?? bounds;
    const slice = screenshotSlice(itemBounds, bounds, state.snapshot.screenshot?.dataUrl);
    plane.style.left = `${((itemBounds.left - bounds.left) / width) * 100}%`;
    plane.style.top = `${((itemBounds.top - bounds.top) / height) * 100}%`;
    plane.style.width = `${Math.max(4, ((itemBounds.right - itemBounds.left) / width) * 100)}%`;
    plane.style.height = `${Math.max(4, ((itemBounds.bottom - itemBounds.top) / height) * 100)}%`;
    plane.style.transform = `translateZ(${z}px)`;
    if (slice) {
      plane.classList.add('has-screenshot');
      plane.style.backgroundImage = `url("${slice.dataUrl}")`;
      plane.style.backgroundSize = `${slice.sizeX}% ${slice.sizeY}%`;
      plane.style.backgroundPosition = `${slice.positionX}% ${slice.positionY}%`;
      plane.style.backgroundRepeat = 'no-repeat';
    }
    const selected = windowMode
      ? item.id === state.selectedWindowId && !state.selectedNodeId
      : item.id === state.selectedNodeId;
    if (selected) plane.classList.add('selected');
    if (findingIds.has(item.id)) plane.classList.add('finding');
    if (windowMode) appendWindowViews(plane, item, findingIds);
    plane.append(el('span', 'plane-label', windowMode ? windowLabel(item) : item.className?.split('.').at(-1) ?? item.id));
    plane.addEventListener('click', () => windowMode ? selectWindow(item.id) : selectNodeId(item.id));
    if (windowMode) plane.addEventListener('keydown', event => {
      if (event.target !== plane || !['Enter', ' '].includes(event.key)) return;
      event.preventDefault();
      selectWindow(item.id);
    });
    elements.scene.append(plane);
  }
  updateSceneTransform();
}

function appendWindowViews(plane, window, findingIds) {
  for (const node of window.nodes ?? []) {
    const geometry = relativeBounds(node.visibleBounds ?? node.bounds, window.bounds);
    if (!geometry) continue;
    const box = el('button', `window-view-box${node.id === state.selectedNodeId ? ' selected' : ''}${findingIds.has(node.id) ? ' finding' : ''}`);
    box.type = 'button';
    box.dataset.nodeId = node.id;
    box.title = node.className ?? node.id;
    box.setAttribute('aria-label', node.className?.split('.').at(-1) ?? node.id);
    box.style.left = `${geometry.left}%`;
    box.style.top = `${geometry.top}%`;
    box.style.width = `${Math.max(.5, geometry.width)}%`;
    box.style.height = `${Math.max(.5, geometry.height)}%`;
    box.style.zIndex = String(windowViewZIndex(node));
    box.addEventListener('click', event => {
      event.stopPropagation();
      selectNodeId(node.id, true);
    });
    plane.append(box);
  }
}

function renderProperties() {
  const target = selectedTarget();
  const targetId = target?.id ?? null;
  const scrollTop = propertyPanelScrollTop(
    state.renderedPropertyTargetId,
    targetId,
    elements['property-panel'].scrollTop
  );
  state.renderedPropertyTargetId = targetId;
  elements.properties.replaceChildren();
  if (!target) {
    elements['property-panel'].scrollTop = 0;
    elements.properties.append(el('div', 'property-empty', '从左侧树、2D 框或 3D 平面选择一个节点。'));
    return;
  }
  elements['selected-title'].textContent = target.className?.split('.').at(-1) ?? target.title ?? 'Window 属性';
  elements['selected-title'].title = target.className ?? target.title ?? '';
  elements['selected-source'].textContent = target.source === 'JVMTI' ? 'Real View' : 'Semantic';
  for (const group of groupProperties(target)) {
    const details = el('details', 'property-group');
    details.open = propertyGroupOpen(state.propertyGroupState, target.id, group.name);
    details.addEventListener('toggle', () => {
      rememberPropertyGroupOpen(state.propertyGroupState, target.id, group.name, details.open);
    });
    const entries = Object.entries(group.properties);
    const summary = el('summary');
    summary.append(el('strong', '', groupLabel(group.name)), el('span', '', String(entries.length)));
    details.append(summary);
    const table = el('div', 'property-table');
    if (!entries.length) {
      table.append(el('div', 'property-name', '—'), el('div', 'property-value', '当前后端未提供'));
    } else for (const [name, property] of entries) {
      const key = el('div', 'property-name', name);
      const value = propertyValue(target, name, property);
      table.append(key, value);
    }
    details.append(table);
    elements.properties.append(details);
  }
  elements['property-panel'].scrollTop = scrollTop;
}

function propertyValue(target, name, property) {
  const container = el('div', 'property-value');
  const targetId = target.id;
  const editId = `${targetId}\u0000${name}`;
  const editing = state.editingProperty?.id === editId;
  const model = propertyEditorModel(property);
  if (editing && model) {
    const editor = el('div', `property-editor${state.mutationBusy ? ' busy' : ''}`);
    const control = editorControl(property, model, state.editingProperty.inputValue);
    control.disabled = state.mutationBusy;
    control.addEventListener(control.type === 'checkbox' ? 'change' : 'input', () => {
      state.editingProperty.inputValue = control.type === 'checkbox'
        ? String(control.checked) : control.value;
    });
    const actions = el('div', 'property-actions');
    const apply = actionButton('应用', 'apply');
    const cancel = actionButton('取消', 'cancel');
    apply.disabled = state.mutationBusy;
    cancel.disabled = state.mutationBusy;
    apply.addEventListener('click', () => mutateProperty(
      target, name, property, state.editingProperty.inputValue));
    cancel.addEventListener('click', () => {
      state.editingProperty = null;
      renderProperties();
    });
    actions.append(apply, cancel);
    editor.append(control);
    if (property.editor.valueType === 'COLOR') editor.append(colorSwatch(state.editingProperty.inputValue));
    editor.append(actions);
    if (state.editingProperty.error) {
      editor.append(el('div', 'property-editor-error', state.editingProperty.error));
    }
    container.append(editor);
  } else {
    const valueSpan = el('span', 'property-value-main', String(property?.value ?? ''));
    if (property?.value && String(property.value).length > 40) {
      valueSpan.title = String(property.value);
    }
    container.append(valueSpan);
    if (model && target.source === 'JVMTI') {
      const actions = el('div', 'property-actions');
      const edit = actionButton('编辑', 'edit');
      edit.addEventListener('click', () => {
        state.editingProperty = {
          id: editId,
          targetId,
          name,
          inputValue: String(property?.value ?? ''),
          error: null
        };
        renderProperties();
      });
      actions.append(edit);
      const originalKey = mutationKey(elements['process-select'].value, targetId, name);
      const original = state.originalValues.get(originalKey);
      if (original !== undefined && original !== String(property?.value ?? '')) {
        const restore = actionButton('恢复', 'restore');
        restore.disabled = state.mutationBusy;
        restore.addEventListener('click', () => mutateProperty(target, name, property, original));
        actions.append(restore);
      }
      container.append(actions);
    } else if (property?.readOnlyReason) {
      const reason = el('span', 'property-readonly', property.readOnlyReason);
      reason.title = property.readOnlyReason;
      container.append(reason);
    }
  }
  container.append(el('span', 'property-source', property?.source ?? target.source ?? ''));
  return container;
}

function editorControl(property, model, inputValue) {
  if (model.control === 'checkbox') {
    const input = el('input', 'property-editor-control');
    input.type = 'checkbox';
    input.checked = String(inputValue).toLowerCase() === 'true';
    input.setAttribute('aria-label', '布尔值');
    return input;
  }
  if (model.control === 'select') {
    const select = el('select', 'property-editor-control');
    const values = model.choices.includes(String(inputValue))
      ? model.choices : [String(inputValue), ...model.choices];
    select.replaceChildren(...values.map(value => option(value, value)));
    select.value = String(inputValue);
    return select;
  }
  const input = el('input', 'property-editor-control');
  input.type = 'text';
  input.value = String(inputValue);
  input.autocomplete = 'off';
  input.spellcheck = false;
  if (property.editor.valueType === 'CHARACTER') input.maxLength = 1;
  return input;
}

function colorSwatch(value) {
  const swatch = el('span', 'property-color-swatch');
  if (/^#[0-9a-f]{6}([0-9a-f]{2})?$/i.test(value)) swatch.style.background = value;
  swatch.title = value;
  return swatch;
}

function actionButton(label, action) {
  const button = el('button', `property-action ${action}`, label);
  button.type = 'button';
  return button;
}

async function mutateProperty(target, name, property, value) {
  if (state.mutationBusy) return;
  const serial = elements['device-select'].value;
  const processOption = elements['process-select'].selectedOptions[0];
  const identity = targetIdentity(target);
  if (!serial || !processOption?.value || !identity) {
    if (state.editingProperty) state.editingProperty.error = '当前目标已变化，请重新采集。';
    renderProperties();
    return;
  }
  state.mutationBusy = true;
  if (state.editingProperty) state.editingProperty.error = null;
  renderProperties();
  try {
    const result = await api('api/mutate', {
      method: 'POST',
      body: JSON.stringify({
        serial,
        pid: Number(processOption.value),
        packageName: processOption.dataset.package,
        processName: processOption.dataset.process,
        targetId: target.id,
        expectedClassName: identity.className ?? null,
        expectedResourceId: identity.resourceId ?? null,
        propertyName: name,
        value: String(value)
      })
    });
    if (!result.success || !result.snapshot) throw new Error(result.message ?? result.error ?? '属性修改失败。');
    const originalKey = mutationKey(processOption.value, target.id, name);
    rememberOriginalValue(state.originalValues, originalKey, String(property?.value ?? ''));
    state.editingProperty = null;
    applyMutationSnapshot(result.snapshot, target);
    toast(`已应用 ${name}`);
  } catch (error) {
    if (state.editingProperty) state.editingProperty.error = error.message ?? String(error);
    toast(error.message ?? String(error), true);
  } finally {
    state.mutationBusy = false;
    renderProperties();
  }
}

function targetIdentity(target) {
  if (target.className) return { className: target.className, resourceId: target.resourceId ?? null };
  const root = target.rootNodeId ? state.snapshot?.nodes?.[target.rootNodeId] : null;
  return root ? { className: root.className, resourceId: root.resourceId ?? null } : null;
}

function applyMutationSnapshot(snapshot, previousTarget) {
  const displayId = state.displayId;
  const selectedWindowId = state.selectedWindowId;
  const selectedNodeId = state.selectedNodeId;
  const identity = targetIdentity(previousTarget);
  state.snapshot = snapshot;
  state.findings = evaluateRules(snapshot, elements['rules-toggle'].checked);
  updateAccessibilityAudit(snapshot, false);
  const displays = [...new Set((snapshot.windows ?? []).map(window => window.displayId))].sort((a, b) => a - b);
  elements['display-select'].replaceChildren(...(displays.length ? displays : [0]).map(value => option(String(value), String(value))));
  state.displayId = displays.includes(displayId) ? displayId : displays[0] ?? 0;
  elements['display-select'].value = String(state.displayId);
  state.expanded = new Set([...state.expanded].filter(id => snapshot.windows.some(window => window.id === id)));
  const selectedWindow = snapshot.windows.find(window => window.id === selectedWindowId);
  const selectedNode = selectedNodeId ? snapshot.nodes[selectedNodeId] : null;
  state.selectedWindowId = selectedWindow?.id ?? null;
  state.selectedNodeId = selectedNode?.id ?? null;
  const refreshedTarget = state.selectedNodeId
    ? selectedNode
    : snapshot.windows.find(window => window.id === state.selectedWindowId);
  const refreshedIdentity = refreshedTarget ? targetIdentity(refreshedTarget) : null;
  if (!refreshedTarget || !identity || !refreshedIdentity
      || identity.className !== refreshedIdentity.className
      || identity.resourceId !== refreshedIdentity.resourceId) {
    state.originalValues.clear();
    chooseDefaultSelection();
  }
  render();
}

function clearMutationState() {
  state.editingProperty = null;
  state.mutationBusy = false;
  state.originalValues.clear();
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

function updateAccessibilityAudit(snapshot, resetFilter) {
  state.accessibilityAudit = auditAccessibility(snapshot);
  if (resetFilter) {
    state.accessibilityFilter = state.accessibilityAudit.issues.length ? 'issues' : 'enabled';
  }
}

function renderAccessibilityAudit() {
  const visible = Boolean(state.snapshot);
  elements['accessibility-audit'].hidden = !visible;
  if (!visible) return;
  const audit = state.accessibilityAudit;
  elements['accessibility-audit-count'].textContent = String(audit.issues.length);
  document.querySelectorAll('[data-accessibility-filter]').forEach(button => {
    const name = button.dataset.accessibilityFilter;
    const count = audit[name]?.length ?? 0;
    button.setAttribute('aria-pressed', String(name === state.accessibilityFilter));
    button.querySelector('span').textContent = String(count);
  });
  const results = audit[state.accessibilityFilter] ?? [];
  elements['accessibility-audit-list'].replaceChildren();
  if (!results.length) {
    elements['accessibility-audit-list'].append(el('p', 'audit-empty', '当前分类没有 View。'));
    return;
  }
  for (const result of results) {
    const node = state.snapshot.nodes?.[result.nodeId];
    if (!node) continue;
    const label = node.resourceId?.split('/').at(-1)
      ?? node.text
      ?? node.className?.split('.').at(-1)
      ?? node.id;
    const detail = result.missingProperties.length
      ? `缺少：${result.missingProperties.join(' / ')}`
      : result.reason;
    const button = el('button', `audit-result${node.id === state.selectedNodeId ? ' selected' : ''}`);
    button.type = 'button';
    button.append(el('span', 'audit-result-title', label), el('span', 'audit-result-detail', detail));
    button.addEventListener('click', () => selectNodeId(node.id, true));
    elements['accessibility-audit-list'].append(button);
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
  const window = state.snapshot.windows.find(value => value.id === id);
  if (!window) return;
  if (state.threeMode === 'views' && window.rootNodeId) {
    selectNodeId(window.rootNodeId);
    return;
  }
  state.selectedWindowId = id;
  state.selectedNodeId = null;
  state.editingProperty = null;
  render();
  clearHighlightOnDevice();
}

function selectNodeId(id, revealTree = false) {
  const selection = selectNode(state.snapshot, state, id);
  if (!selection) return;
  revealAncestors(id);
  state = { ...state, ...selection };
  render();
  if (revealTree) revealTreeNode(id);
  clearHighlightOnDevice();
  highlightOnDevice();
}

let highlightTimer = null;

async function highlightOnDevice() {
  clearTimeout(highlightTimer);
  const target = selectedTarget();
  const nodeId = target?.id ?? null;
  if (!nodeId) return;
  highlightTimer = setTimeout(async () => {
    try {
      await api('api/highlight', {
        method: 'POST',
        body: JSON.stringify({ nodeId })
      });
    } catch (ignored) {
      // Highlight is non-critical; suppress errors.
    }
  }, 120);
}

async function clearHighlightOnDevice() {
  clearTimeout(highlightTimer);
  try {
    await api('api/highlight', {
      method: 'POST',
      body: JSON.stringify({ nodeId: '' })
    });
  } catch (ignored) {
    // Highlight is non-critical; suppress errors.
  }
}

function revealAncestors(id) {
  const node = state.snapshot?.nodes?.[id];
  if (!node) return;
  const expanded = new Set(state.expanded ?? []);
  expanded.add(node.windowId);
  let current = node;
  while (current?.parentId && state.snapshot.nodes[current.parentId]) {
    current = state.snapshot.nodes[current.parentId];
    expanded.add(current.id);
  }
  state.expanded = expanded;
}

function revealTreeNode(id) {
  const row = [...elements.tree.querySelectorAll('[data-node-id]')]
    .find(value => value.dataset.nodeId === id);
  if (row && outsideViewport(row.getBoundingClientRect(), elements.tree.getBoundingClientRect())) {
    row.scrollIntoView({ block: 'nearest' });
  }
}

function selectedTarget() {
  if (!state.snapshot) return null;
  return (state.selectedNodeId && state.snapshot.nodes[state.selectedNodeId])
    || state.snapshot.windows.find(window => window.id === state.selectedWindowId)
    || null;
}

function bindSceneControls() {
  let drag = null;
  const stage = elements['three-d-stage'];
  const clearDrag = () => {
    drag = null;
    stage.classList.remove('dragging');
  };
  const suppressGeneratedClick = () => {
    const suppress = event => {
      event.preventDefault();
      event.stopImmediatePropagation();
    };
    stage.addEventListener('click', suppress, { capture: true, once: true });
    setTimeout(() => stage.removeEventListener('click', suppress, true), 0);
  };

  stage.addEventListener('pointerdown', event => {
    drag = {
      x: event.clientX,
      y: event.clientY,
      rx: state.rotationX,
      rz: state.rotationZ,
      pointerId: event.pointerId,
      active: false
    };
  });
  stage.addEventListener('pointermove', event => {
    if (!drag || event.pointerId !== drag.pointerId) return;
    if (!drag.active) {
      if (!isDragGesture(drag.x, drag.y, event.clientX, event.clientY)) return;
      drag.active = true;
      stage.setPointerCapture(event.pointerId);
      stage.classList.add('dragging');
    }
    const rotation = dragRotation(
      drag.rx,
      drag.rz,
      event.clientX - drag.x,
      event.clientY - drag.y
    );
    state.rotationX = rotation.rotationX;
    state.rotationZ = rotation.rotationZ;
    updateSceneTransform();
  });
  stage.addEventListener('pointerup', event => {
    if (!drag || event.pointerId !== drag.pointerId) return;
    if (drag.active) suppressGeneratedClick();
    clearDrag();
  });
  stage.addEventListener('pointercancel', clearDrag);
  stage.addEventListener('lostpointercapture', clearDrag);
  stage.addEventListener('wheel', event => {
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

async function fillDevices(devices, autoCapture = false) {
  elements['device-select'].replaceChildren(option('', '选择设备'));
  for (const device of devices) {
    const item = option(device.serial, `${device.model ?? device.serial} · ${device.state}`);
    item.disabled = !device.ready;
    elements['device-select'].append(item);
  }
  const first = devices.find(device => device.ready);
  if (first) {
    elements['device-select'].value = first.serial;
    await loadProcesses(autoCapture);
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
