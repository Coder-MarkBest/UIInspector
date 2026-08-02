export const PROPERTY_GROUP_ORDER = [
  'identity',
  'windowHierarchy',
  'geometryLayout',
  'renderingTransform',
  'content',
  'interactionState',
  'accessibility',
  'captureDiagnostics'
];

export function buildWindowLayers(snapshot, displayId) {
  return (snapshot?.windows ?? [])
    .filter(window => window.displayId === displayId)
    .slice()
    .sort((left, right) => left.layer - right.layer || left.id.localeCompare(right.id));
}

export function buildViewDepths(snapshot, windowId) {
  const nodes = Object.values(snapshot?.nodes ?? {}).filter(node => node.windowId === windowId);
  const order = new Map();
  let sequence = 0;
  const visit = id => {
    const node = snapshot.nodes[id];
    if (!node || node.windowId !== windowId || order.has(id)) return;
    order.set(id, sequence++);
    (node.childIds ?? []).forEach(visit);
  };
  const window = (snapshot?.windows ?? []).find(value => value.id === windowId);
  if (window?.rootNodeId) visit(window.rootNodeId);
  nodes.forEach(node => visit(node.id));
  return nodes.slice().sort((left, right) =>
    left.depth - right.depth || (order.get(left.id) ?? 0) - (order.get(right.id) ?? 0));
}

export function filterDepth(items, depth) {
  if (depth === null || depth === undefined || depth === 'all') return items.slice();
  const numeric = Number(depth);
  return items.filter(item => Number(item.depth ?? item.layer) === numeric);
}

export function depthControl(items, selectedDepth) {
  const levels = [...new Set(items.map(item => Number(item.depth ?? item.layer)))].sort((a, b) => a - b);
  const selected = selectedDepth !== 'all' && levels.includes(Number(selectedDepth))
    ? Number(selectedDepth) : 'all';
  return {
    levels,
    selected,
    value: selected === 'all' ? levels.length : levels.indexOf(selected),
    maximum: levels.length
  };
}

export function previewPoint(frameRect, deviceBounds, clientX, clientY) {
  const frameWidth = Number(frameRect?.right) - Number(frameRect?.left);
  const frameHeight = Number(frameRect?.bottom) - Number(frameRect?.top);
  const deviceWidth = Number(deviceBounds?.right) - Number(deviceBounds?.left);
  const deviceHeight = Number(deviceBounds?.bottom) - Number(deviceBounds?.top);
  if (![frameWidth, frameHeight, deviceWidth, deviceHeight, clientX, clientY].every(Number.isFinite)
      || frameWidth <= 0 || frameHeight <= 0 || deviceWidth <= 0 || deviceHeight <= 0) return null;
  return {
    x: Number(deviceBounds.left) + ((clientX - Number(frameRect.left)) / frameWidth) * deviceWidth,
    y: Number(deviceBounds.top) + ((clientY - Number(frameRect.top)) / frameHeight) * deviceHeight
  };
}

export function pickNodeAt(snapshot, displayId, x, y) {
  if (!Number.isFinite(x) || !Number.isFinite(y)) return null;
  const windows = new Map((snapshot?.windows ?? [])
    .filter(window => window.displayId === displayId)
    .map(window => [window.id, window]));
  return Object.values(snapshot?.nodes ?? {})
    .map(node => ({
      node,
      window: windows.get(node.windowId),
      hit: positiveRect(node.visibleBounds) ?? positiveRect(node.bounds)
    }))
    .filter(value => value.window && value.hit && containsPoint(value.hit, x, y))
    .sort(compareHitCandidates)
    .at(0)?.node ?? null;
}

export function outsideViewport(item, container) {
  if (!item || !container) return false;
  return item.top < container.top || item.bottom > container.bottom
    || item.left < container.left || item.right > container.right;
}

export function dragRotation(rotationX, rotationZ, deltaX, deltaY) {
  return {
    rotationX: Math.max(18, Math.min(78, rotationX - deltaY * .25)),
    rotationZ: rotationZ - deltaX * .25
  };
}

export function groupProperties(target) {
  const source = new Map((target?.propertyGroups ?? []).map(group => [group.name, group.properties ?? {}]));
  return PROPERTY_GROUP_ORDER.map(name => ({ name, properties: source.get(name) ?? {} }));
}

export function selectNode(state, nodeId) {
  return { ...state, selectedNodeId: nodeId };
}

export function propertyEditorModel(property) {
  if (!property?.editor) return null;
  const choices = Array.isArray(property.editor.choices) ? property.editor.choices.slice() : [];
  if (property.editor.valueType === 'BOOLEAN') {
    return { control: 'checkbox', value: String(property.value).toLowerCase() === 'true', choices };
  }
  if (property.editor.valueType === 'ENUM' || choices.length) {
    return { control: 'select', value: String(property.value ?? ''), choices };
  }
  return { control: 'text', value: String(property.value ?? ''), choices };
}

export function mutationKey(pid, targetId, propertyName) {
  return `${String(pid)}\u0000${String(targetId)}\u0000${String(propertyName)}`;
}

export function rememberOriginalValue(history, key, value) {
  if (!history.has(key)) history.set(key, value);
  return history;
}

export function evaluateRules(snapshot, enabled) {
  if (!enabled) return [];
  const windows = new Map((snapshot?.windows ?? []).map(window => [window.id, window]));
  const findings = [];
  for (const node of Object.values(snapshot?.nodes ?? {})) {
    const window = windows.get(node.windowId);
    if (window && outside(node.bounds, window.bounds)) {
      findings.push({
        ruleId: 'layout.outsideWindow',
        severity: 'warning',
        nodeId: node.id,
        reason: 'View bounds extend outside the selected Window.',
        evidence: { nodeBounds: node.bounds, windowBounds: window.bounds }
      });
    }
    const properties = flattenProperties(node);
    const bitmapByteCount = number(properties.bitmapByteCount);
    const intrinsicWidth = number(properties.drawableIntrinsicWidth);
    const intrinsicHeight = number(properties.drawableIntrinsicHeight);
    const displayedArea = area(node.bounds);
    const intrinsicArea = intrinsicWidth * intrinsicHeight;
    if (bitmapByteCount >= 16 * 1024 * 1024 || (displayedArea > 0 && intrinsicArea / displayedArea >= 4)) {
      findings.push({
        ruleId: 'image.oversized',
        severity: 'warning',
        nodeId: node.id,
        reason: 'Image memory or intrinsic dimensions are large relative to its displayed bounds.',
        evidence: { bitmapByteCount, intrinsicWidth, intrinsicHeight, displayedArea }
      });
    }
  }
  return findings;
}

export function flattenProperties(target) {
  const values = {};
  for (const group of target?.propertyGroups ?? []) {
    for (const [name, property] of Object.entries(group.properties ?? {})) {
      values[name] = property?.value ?? property;
    }
  }
  return values;
}

function outside(inner, outer) {
  if (!inner || !outer) return false;
  return inner.left < outer.left || inner.top < outer.top
    || inner.right > outer.right || inner.bottom > outer.bottom;
}

function area(bounds) {
  if (!bounds) return 0;
  return Math.max(0, bounds.right - bounds.left) * Math.max(0, bounds.bottom - bounds.top);
}

function number(value) {
  const parsed = Number(value ?? 0);
  return Number.isFinite(parsed) ? parsed : 0;
}

function compareHitCandidates(left, right) {
  return right.window.layer - left.window.layer
    || right.node.depth - left.node.depth
    || Number(right.node.drawingOrder ?? Number.MIN_SAFE_INTEGER)
      - Number(left.node.drawingOrder ?? Number.MIN_SAFE_INTEGER)
    || rectArea(left.hit) - rectArea(right.hit)
    || left.node.id.localeCompare(right.node.id);
}

function positiveRect(value) {
  if (!value) return null;
  const numbers = [value.left, value.top, value.right, value.bottom].map(Number);
  if (!numbers.every(Number.isFinite) || numbers[2] <= numbers[0] || numbers[3] <= numbers[1]) return null;
  return { left: numbers[0], top: numbers[1], right: numbers[2], bottom: numbers[3] };
}

function containsPoint(bounds, x, y) {
  return x >= bounds.left && x < bounds.right && y >= bounds.top && y < bounds.bottom;
}

function rectArea(bounds) {
  return (bounds.right - bounds.left) * (bounds.bottom - bounds.top);
}
