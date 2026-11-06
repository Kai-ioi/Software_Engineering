import { createApp } from 'vue'
import { ElButton, ElInput, ElForm, ElFormItem, ElTag, ElSelect, ElOption, ElEmpty, ElAlert, ElDialog, ElUpload, ElDatePicker, ElInputNumber, ElMessage, ElLoading, ElTable, ElTableColumn, ElTabs, ElTabPane, ElCheckbox, ElSwitch, ElCollapse, ElCollapseItem } from 'element-plus'
import 'element-plus/dist/index.css'
import App from './App.vue'
import { ElConfigProvider } from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import { h } from 'vue'
import router from './router'
import './styles.css'
const app = createApp({ render: () => h(ElConfigProvider, { locale: zhCn }, { default: () => h(App) }) })
for (const component of [ElButton, ElInput, ElForm, ElFormItem, ElTag, ElSelect, ElOption, ElEmpty, ElAlert, ElDialog, ElUpload, ElDatePicker, ElInputNumber, ElLoading, ElTable, ElTableColumn, ElTabs, ElTabPane, ElCheckbox, ElSwitch, ElCollapse, ElCollapseItem]) app.use(component)
app.config.errorHandler = () => { ElMessage.error('页面处理失败，请刷新后重试') }
app.use(router).mount('#app')

if (import.meta.env.DEV) void import('./devDemoPointer').then(m => m.installDemoPointer())
