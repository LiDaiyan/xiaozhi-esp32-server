<template>
  <div>
  <el-dialog title="项目与设备白名单" :visible="visible" @update:visible="$emit('update:visible', $event)"
    width="850px" :close-on-click-modal="false" @open="loadProjects" @closed="contextDialogVisible = false">
    <div v-loading="loading || saving">
      <el-form inline size="small" @submit.native.prevent>
        <el-form-item label="Project-Id">
          <el-input v-model="form.projectId" :disabled="editing" maxlength="64" placeholder="如 factory-a" />
        </el-form-item>
        <el-form-item label="项目名称">
          <el-input v-model="form.projectName" maxlength="100" />
        </el-form-item>
        <el-form-item label="允许接入">
          <el-switch v-model="form.enabled" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :disabled="saving" @click="saveProject">{{ editing ? '保存项目' : '创建项目' }}</el-button>
          <el-button v-if="editing" @click="resetForm">取消编辑</el-button>
        </el-form-item>
      </el-form>
      <el-table :data="projects" size="small" max-height="230">
        <el-table-column prop="projectId" label="Project-Id" min-width="130" />
        <el-table-column prop="projectName" label="项目名称" min-width="130" />
        <el-table-column label="接入状态" width="90">
          <template slot-scope="scope">{{ scope.row.enabled === 1 ? '允许' : '停用' }}</template>
        </el-table-column>
        <el-table-column label="上下文源" width="90">
          <template slot-scope="scope">{{ (scope.row.contextProviders || []).length }} 个</template>
        </el-table-column>
        <el-table-column label="操作" width="220">
          <template slot-scope="scope">
            <el-button type="text" @click="editProject(scope.row)">编辑</el-button>
            <el-button type="text" @click="selectProject(scope.row.projectId)">白名单</el-button>
            <el-button type="text" @click="openProjectContext(scope.row)">上下文源</el-button>
          </template>
        </el-table-column>
      </el-table>
      <p class="context-hint">智能体未单独配置上下文源时，自动使用所属项目的配置；修改后设备重新连接生效。</p>
      <template v-if="selectedProjectId">
        <h3>{{ selectedProjectId }} · MAC 白名单</h3>
        <p>每台白名单设备首次请求 OTA 时自动创建一个智能体，并归属于创建项目的账号。</p>
        <el-input v-model="macText" type="textarea" :rows="4"
          placeholder="粘贴 MAC 地址，每行一个，也可用逗号或空格分隔。支持导入 .txt / .csv 文件。" />
        <div class="import-actions">
          <input ref="macFile" type="file" accept=".txt,.csv" @change="readFile" />
          <el-button size="small" type="primary" :disabled="saving || !macText.trim()" @click="importMacs">追加到白名单</el-button>
          <span>共 {{ whitelist.length }} 台设备，重复 MAC 自动跳过</span>
        </div>
        <el-table :data="whitelist" size="small" max-height="260" v-loading="devicesLoading">
          <el-table-column prop="macAddress" label="Device-Id / MAC 地址" />
          <el-table-column label="操作" width="80">
            <template slot-scope="scope">
              <el-button type="text" :disabled="saving" @click="removeMac(scope.row.macAddress)">移除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </template>
    </div>
  </el-dialog>
  <ContextProviderDialog :visible.sync="contextDialogVisible" :providers="contextProviders"
    :title="contextProjectId + ' · 项目上下文源'" @confirm="saveProjectContext" />
  </div>
</template>

<script>
import projectApi from '@/apis/module/project';
import ContextProviderDialog from './ContextProviderDialog.vue';

export default {
  name: 'ProjectManagementDialog',
  components: { ContextProviderDialog },
  props: { visible: Boolean },
  data() {
    return {
      projects: [], loading: false, saving: false, devicesLoading: false,
      editing: false, form: { projectId: '', projectName: '', enabled: true },
      selectedProjectId: '', whitelist: [], macText: '',
      contextDialogVisible: false, contextProjectId: '', contextProviders: [],
    };
  },
  methods: {
    async loadProjects() {
      this.loading = true;
      try {
        this.projects = await projectApi.list() || [];
        this.$emit('changed', this.projects);
        if (this.selectedProjectId) await this.loadDevices();
      } catch (error) { this.$message.error(error.message); }
      finally { this.loading = false; }
    },
    resetForm() {
      this.editing = false;
      this.form = { projectId: '', projectName: '', enabled: true };
    },
    editProject(project) {
      this.editing = true;
      this.form = { projectId: project.projectId, projectName: project.projectName, enabled: project.enabled === 1 };
    },
    async saveProject() {
      if (!/^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$/.test(this.form.projectId) || !this.form.projectName.trim()) {
        this.$message.error('请填写有效的 Project-Id 和项目名称，ID 支持字母、数字、下划线和连字符');
        return;
      }
      this.saving = true;
      try {
        const id = this.form.projectId;
        if (this.editing) await projectApi.update(id, this.form);
        else await projectApi.create(this.form);
        this.resetForm();
        await this.loadProjects();
        await this.selectProject(id);
        this.$message.success('项目已保存');
      } catch (error) { this.$message.error(error.message); }
      finally { this.saving = false; }
    },
    async openProjectContext(project) {
      this.loading = true;
      try {
        const providers = await projectApi.contextProviders(project.projectId);
        this.contextProjectId = project.projectId;
        this.contextProviders = providers || [];
        if (this.visible) this.contextDialogVisible = true;
      } catch (error) { this.$message.error(error.message); }
      finally { this.loading = false; }
    },
    async saveProjectContext(providers) {
      this.contextProviders = providers;
      this.saving = true;
      try {
        await projectApi.updateContextProviders(this.contextProjectId, providers);
        this.$message.success('项目上下文源已保存，设备重新连接后生效');
        await this.loadProjects();
      } catch (error) {
        this.$message.error(error.message);
        if (this.visible) this.contextDialogVisible = true;
      } finally { this.saving = false; }
    },
    async selectProject(id) {
      this.selectedProjectId = id;
      this.macText = '';
      this.whitelist = [];
      await this.loadDevices();
    },
    async loadDevices() {
      const id = this.selectedProjectId;
      this.devicesLoading = true;
      try {
        const devices = await projectApi.devices(id);
        if (this.selectedProjectId === id) this.whitelist = devices || [];
      } catch (error) { this.$message.error(error.message); }
      finally { if (this.selectedProjectId === id) this.devicesLoading = false; }
    },
    async readFile(event) {
      const file = event.target.files[0];
      if (!file) return;
      try {
        if (file.size > 1024 * 1024) throw new Error('文件不能超过 1 MiB');
        this.macText = await file.text();
      } catch (error) { this.$message.error(error.message); }
      finally { event.target.value = ''; }
    },
    async importMacs() {
      const macs = this.macText.split(/[\s,，;；]+/).filter(Boolean);
      if (!macs.length || macs.length > 5000 || macs.some(mac => !/^([0-9a-f]{2}[:-]){5}[0-9a-f]{2}$/i.test(mac))) {
        this.$message.error('每次最多导入 5000 个有效 MAC 地址，请去掉表头和其他内容');
        return;
      }
      this.saving = true;
      try {
        await projectApi.importDevices(this.selectedProjectId, macs);
        this.macText = '';
        await this.loadDevices();
        this.$message.success('白名单已更新');
      } catch (error) { this.$message.error(error.message); }
      finally { this.saving = false; }
    },
    async removeMac(mac) {
      try {
        await this.$confirm(`移除 ${mac} 后，该设备后续请求 OTA 和建立新对话将被拒绝。智能体和历史记录保留。`, '移除白名单', { type: 'warning' });
      } catch { return; }
      this.saving = true;
      try {
        await projectApi.removeDevice(this.selectedProjectId, mac);
        await this.loadDevices();
      } catch (error) { this.$message.error(error.message); }
      finally { this.saving = false; }
    },
  },
};
</script>

<style scoped>
h3 { margin: 24px 0 8px; font-size: 16px; }
p, .import-actions span { color: #606266; font-size: 13px; }
.import-actions { display: flex; gap: 12px; align-items: center; margin: 12px 0; }
.import-actions input { width: 260px; }
</style>
