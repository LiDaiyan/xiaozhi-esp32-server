/** Group both search results and the complete agent list, retaining empty projects outside searches. */
export function groupAgentsByProject(projects, agents, selectedProject = 'all', isSearching = false) {
  const groups = projects.map(project => ({
    key: 'project:' + project.projectId,
    projectId: project.projectId,
    name: project.projectName,
    agents: [],
  }));
  const legacy = { key: 'legacy', projectId: null, name: '未分组', agents: [] };
  const byId = new Map(groups.map(group => [group.projectId, group]));
  agents.forEach(agent => {
    if (!agent.projectId) legacy.agents.push(agent);
    else {
      if (!byId.has(agent.projectId)) {
        const group = { key: 'project:' + agent.projectId, projectId: agent.projectId, name: agent.projectId, agents: [] };
        groups.push(group);
        byId.set(agent.projectId, group);
      }
      byId.get(agent.projectId).agents.push(agent);
    }
  });
  groups.push(legacy);
  return groups.filter(group => (selectedProject === 'all' || selectedProject === group.key)
    && (!isSearching || group.agents.length));
}
