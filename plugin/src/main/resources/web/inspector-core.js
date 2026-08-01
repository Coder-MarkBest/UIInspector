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

export function groupProperties(target) {
  const source = new Map((target?.propertyGroups ?? []).map(group => [group.name, group.properties ?? {}]));
  return PROPERTY_GROUP_ORDER.map(name => ({ name, properties: source.get(name) ?? {} }));
}

export function selectNode(state, nodeId) {
  return { ...state, selectedNodeId: nodeId };
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
