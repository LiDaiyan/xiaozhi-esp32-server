import assert from 'node:assert/strict';
import test from 'node:test';
import { groupAgentsByProject } from '../src/utils/projectGrouping.mjs';

const projects = [
  { projectId: 'all', projectName: '项目一' },
  { projectId: 'p2', projectName: '项目二' },
  { projectId: 'empty', projectName: '新项目' },
];
const agents = [
  { id: 'a1', projectId: 'all' }, { id: 'a2', projectId: 'p2' },
  { id: 'old' }, { id: 'unknown', projectId: 'pending' },
];

test('projects and legacy agents stay in separate groups, including unknown project IDs', () => {
  const groups = groupAgentsByProject(projects, agents);
  assert.deepEqual(groups.flatMap(group => group.agents.map(agent => agent.id)).sort(), ['a1', 'a2', 'old', 'unknown']);
  assert.equal(groups.find(group => group.projectId === 'all').name, '项目一');
  assert.deepEqual(groups.find(group => group.key === 'legacy').agents.map(agent => agent.id), ['old']);
  assert.equal(groups.find(group => group.projectId === 'empty').agents.length, 0);
});

test('Project-Id named all does not collide with the all-projects filter', () => {
  const groups = groupAgentsByProject(projects, agents, 'project:all');
  assert.equal(groups.length, 1);
  assert.deepEqual(groups[0].agents.map(agent => agent.id), ['a1']);
});

test('search results respect the selected project and hide empty groups', () => {
  assert.deepEqual(groupAgentsByProject(projects, [agents[0]], 'project:p2', true), []);
  assert.deepEqual(groupAgentsByProject(projects, [agents[1]], 'all', true).map(group => group.projectId), ['p2']);
});
