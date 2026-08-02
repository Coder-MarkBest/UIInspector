import assert from 'node:assert/strict';
import test from 'node:test';
import {
  buildViewDepths,
  buildWindowLayers,
  depthControl,
  dragRotation,
  evaluateRules,
  filterDepth,
  groupProperties,
  mutationKey,
  outsideViewport,
  pickNodeAt,
  preferredProjectProcess,
  previewPoint,
  propertyEditorModel,
  propertyGroupOpen,
  rememberOriginalValue,
  rememberPropertyGroupOpen,
  selectNode,
  shouldAutoCapture
} from '../../main/resources/web/inspector-core.js';

const groups = [
  'identity', 'windowHierarchy', 'geometryLayout', 'renderingTransform',
  'content', 'interactionState', 'accessibility', 'captureDiagnostics'
];

function snapshot() {
  return {
    windows: [
      { id: 'dialog', displayId: 0, layer: 9, parentWindowId: 'activity', rootNodeId: 'dialog-root', bounds: rect(0, 0, 200, 160) },
      { id: 'activity', displayId: 0, layer: 2, parentWindowId: null, rootNodeId: 'root', bounds: rect(0, 0, 800, 480) },
      { id: 'cluster', displayId: 1, layer: 4, parentWindowId: null, rootNodeId: 'cluster-root', bounds: rect(0, 0, 400, 240) }
    ],
    nodes: {
      root: node('root', 'activity', null, ['child'], 0, rect(0, 0, 800, 480)),
      child: node('child', 'activity', 'root', [], 1, rect(-20, 0, 900, 600)),
      'dialog-root': node('dialog-root', 'dialog', null, [], 0, rect(0, 0, 200, 160)),
      'cluster-root': node('cluster-root', 'cluster', null, [], 0, rect(0, 0, 400, 240))
    }
  };
}

test('Window layers sort by z without replacing Window parent relationships', () => {
  const layers = buildWindowLayers(snapshot(), 0);
  assert.deepEqual(layers.map(value => value.id), ['activity', 'dialog']);
  assert.equal(layers[1].parentWindowId, 'activity');
  assert.deepEqual(layers.map(value => value.layer), [2, 9]);
});

test('View depth contains only nodes from the selected Window', () => {
  const views = buildViewDepths(snapshot(), 'activity');
  assert.deepEqual(views.map(value => value.id), ['root', 'child']);
  assert.deepEqual(filterDepth(views, 1).map(value => value.id), ['child']);
});

test('depth control maps sparse Android layers to contiguous slider positions', () => {
  const layers = buildWindowLayers(snapshot(), 0).map(window => ({ ...window, depth: window.layer }));
  assert.deepEqual(depthControl(layers, 'all'), {
    levels: [2, 9], selected: 'all', value: 2, maximum: 2
  });
  assert.deepEqual(depthControl(layers, 9), {
    levels: [2, 9], selected: 9, value: 1, maximum: 2
  });
});

test('property groups retain Android order and selection is synchronized', () => {
  const target = snapshot().nodes.root;
  target.propertyGroups = [{ name: 'content', properties: { text: { value: 'Home', source: 'JVMTI' } } }];
  assert.deepEqual(groupProperties(target).map(value => value.name), groups);
  assert.equal(groupProperties(target)[4].properties.text.value, 'Home');
  assert.deepEqual(selectNode({ selectedNodeId: null }, 'child'), { selectedNodeId: 'child' });
});

test('property group state preserves lower groups and explicit default closures', () => {
  const openGroups = new Map();
  assert.equal(propertyGroupOpen(openGroups, 'child', 'identity'), true);
  assert.equal(propertyGroupOpen(openGroups, 'child', 'accessibility'), false);

  rememberPropertyGroupOpen(openGroups, 'child', 'accessibility', true);
  rememberPropertyGroupOpen(openGroups, 'child', 'identity', false);

  assert.equal(propertyGroupOpen(openGroups, 'child', 'accessibility'), true);
  assert.equal(propertyGroupOpen(openGroups, 'child', 'identity'), false);
  assert.equal(propertyGroupOpen(openGroups, 'root', 'identity'), true);
});

test('anomaly checks are off by default and report layout or image evidence when enabled', () => {
  const input = snapshot();
  input.nodes.child.propertyGroups = [{
    name: 'content',
    properties: {
      drawableIntrinsicWidth: { value: '4096', source: 'JVMTI' },
      drawableIntrinsicHeight: { value: '4096', source: 'JVMTI' },
      bitmapByteCount: { value: '67108864', source: 'JVMTI' }
    }
  }];
  assert.deepEqual(evaluateRules(input, false), []);
  const findings = evaluateRules(input, true);
  assert.deepEqual(findings.map(value => value.ruleId), ['layout.outsideWindow', 'image.oversized']);
  assert.equal(findings[1].nodeId, 'child');
  assert.equal(findings[1].evidence.bitmapByteCount, 67108864);
});

test('editor model maps protocol types to safe controls', () => {
  assert.deepEqual(propertyEditorModel({
    value: 'true', editor: { valueType: 'BOOLEAN', choices: [] }
  }), { control: 'checkbox', value: true, choices: [] });
  assert.deepEqual(propertyEditorModel({
    value: 'CENTER_CROP', editor: { valueType: 'ENUM', choices: ['CENTER', 'CENTER_CROP'] }
  }), { control: 'select', value: 'CENTER_CROP', choices: ['CENTER', 'CENTER_CROP'] });
  assert.deepEqual(propertyEditorModel({
    value: '-1', editor: { valueType: 'INTEGER', choices: ['MATCH_PARENT', 'WRAP_CONTENT'] }
  }), { control: 'select', value: '-1', choices: ['MATCH_PARENT', 'WRAP_CONTENT'] });
  assert.equal(propertyEditorModel({ value: 'Drawable', editor: null }), null);
});

test('restore history keeps the first value per process node and property', () => {
  const history = new Map();
  const key = mutationKey('123', 'v:n:0:0', 'alpha');
  rememberOriginalValue(history, key, '1.0');
  rememberOriginalValue(history, key, '0.5');
  assert.equal(history.get(key), '1.0');
});

test('preview coordinates map rendered pixels back to device coordinates', () => {
  assert.deepEqual(
    previewPoint(rect(100, 50, 500, 250), rect(0, 0, 800, 400), 300, 150),
    { x: 400, y: 200 }
  );
  assert.equal(previewPoint(rect(100, 50, 100, 250), rect(0, 0, 800, 400), 100, 100), null);
});

test('2D hit testing selects the deepest node in the highest Window', () => {
  const input = snapshot();
  input.nodes.child.bounds = rect(10, 10, 300, 300);
  input.nodes.child.visibleBounds = rect(10, 10, 200, 200);
  input.nodes.child.drawingOrder = 1;
  input.nodes['dialog-root'].bounds = rect(20, 20, 180, 140);
  input.nodes['dialog-root'].visibleBounds = rect(20, 20, 180, 140);
  input.nodes['dialog-root'].drawingOrder = 0;

  assert.equal(pickNodeAt(input, 0, 50, 50)?.id, 'dialog-root');
  assert.equal(pickNodeAt(input, 0, 190, 190)?.id, 'child');
  assert.equal(pickNodeAt(input, 0, 250, 250)?.id, 'root');
  assert.equal(pickNodeAt(input, 0, 900, 700), null);
});

test('2D hit testing resolves same-Window ties deterministically', () => {
  const input = snapshot();
  input.windows = [input.windows.find(value => value.id === 'activity')];
  input.nodes.root.bounds = rect(0, 0, 400, 400);
  input.nodes.root.visibleBounds = input.nodes.root.bounds;
  input.nodes.root.drawingOrder = 0;
  input.nodes.child.bounds = rect(0, 0, 200, 200);
  input.nodes.child.visibleBounds = input.nodes.child.bounds;
  input.nodes.child.drawingOrder = 2;
  input.nodes.sibling = node('sibling', 'activity', 'root', [], 1, rect(0, 0, 160, 160), 3);

  assert.equal(pickNodeAt(input, 0, 100, 100)?.id, 'sibling');
  input.nodes.sibling.drawingOrder = 2;
  assert.equal(pickNodeAt(input, 0, 100, 100)?.id, 'sibling');
});

test('tree reveal is requested only when the selected row is outside its viewport', () => {
  const viewport = rect(0, 100, 300, 500);
  assert.equal(outsideViewport(rect(0, 120, 300, 160), viewport), false);
  assert.equal(outsideViewport(rect(0, 80, 300, 120), viewport), true);
  assert.equal(outsideViewport(rect(0, 480, 300, 520), viewport), true);
});

test('3D drag follows horizontal pointer direction and preserves vertical clamps', () => {
  assert.deepEqual(dragRotation(58, -27, 40, 20), { rotationX: 53, rotationZ: -37 });
  assert.deepEqual(dragRotation(20, -27, 0, 100), { rotationX: 18, rotationZ: -27 });
  assert.deepEqual(dragRotation(75, -27, 0, -100), { rotationX: 78, rotationZ: -27 });
});

test('preferred project process selects the main process before secondary processes', () => {
  const processes = [
    { pid: 12, packageName: 'com.example.app', processName: 'com.example.app:worker', debuggable: true },
    { pid: 11, packageName: 'com.example.app', processName: 'com.example.app', debuggable: true },
    { pid: 20, packageName: 'com.other', processName: 'com.other', debuggable: true }
  ];

  assert.equal(preferredProjectProcess(processes, ['com.example.app'])?.pid, 11);
  assert.equal(preferredProjectProcess([processes[0], processes[2]], ['com.example.app'])?.pid, 12);
  assert.equal(preferredProjectProcess([processes[2]], ['com.example.app']), null);
});

test('automatic capture runs only once when a project process is available', () => {
  const process = { pid: 11, packageName: 'com.example.app', processName: 'com.example.app' };
  assert.equal(shouldAutoCapture({ attempted: false, process }), true);
  assert.equal(shouldAutoCapture({ attempted: true, process }), false);
  assert.equal(shouldAutoCapture({ attempted: false, process: null }), false);
});

function rect(left, top, right, bottom) { return { left, top, right, bottom }; }
function node(id, windowId, parentId, childIds, depth, bounds, drawingOrder = 0) {
  return {
    id, windowId, parentId, childIds, depth, bounds,
    visibleBounds: bounds, drawingOrder,
    className: 'android.view.View', propertyGroups: []
  };
}
