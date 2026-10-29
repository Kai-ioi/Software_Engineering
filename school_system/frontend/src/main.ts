import { createApp } from 'vue'
import { ElButton, ElInput, ElForm, ElFormItem, ElTag, ElSelect, ElOption, ElEmpty, ElAlert, ElDialog, ElUpload, ElMessage } from 'element-plus'
import 'element-plus/dist/index.css'
import App from './App.vue'
import router from './router'
import './styles.css'
const app = createApp(App)
for (const component of [ElButton, ElInput, ElForm, ElFormItem, ElTag, ElSelect, ElOption, ElEmpty, ElAlert, ElDialog, ElUpload]) app.use(component)
app.config.errorHandler = () => { ElMessage.error('页面处理失败，请刷新后重试') }
app.use(router).mount('#app')
