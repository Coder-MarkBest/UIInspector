import assert from 'node:assert/strict';
import test from 'node:test';
import {
  buildViewDepths,
  buildWindowLayers,
  depthControl,
  evaluateRules,
  filterDepth,
  groupProperties,
  mutationKey,
  propertyEditorModel,
  rememberOriginalValue,
  selectNode
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

function rect(left, top, right, bottom) { return { left, top, right, bottom }; }
function node(id, windowId, parentId, childIds, depth, bounds) {
  return { id, windowId, parentId, childIds, depth, bounds, className: 'android.view.View', propertyGroups: [] };
}
