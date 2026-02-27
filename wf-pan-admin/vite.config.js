import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { resolve } from 'path'
import fs from 'fs'

// 复制到后端目录的插件（仅在生产构建时执行）
const copyToServerPlugin = () => ({
  name: 'copy-to-server',
  closeBundle() {
    // 只在生产构建时执行
    if (process.env.NODE_ENV === 'development') {
      return
    }
    
    const sourceDir = resolve(__dirname, 'dist')
    // 复制到后端的 static 根目录
    const targetDir = resolve(__dirname, '../wf-pan-server/src/main/resources/static')
    
    // 检查源目录是否存在
    if (!fs.existsSync(sourceDir)) {
      console.log('Source directory not found, skipping copy')
      return
    }
    
    // 创建或清空目标目录
    if (fs.existsSync(targetDir)) {
      fs.rmSync(targetDir, { recursive: true })
    }
    fs.mkdirSync(targetDir, { recursive: true })
    
    // 复制文件
    const copyDir = (src, dest) => {
      const entries = fs.readdirSync(src, { withFileTypes: true })
      
      for (const entry of entries) {
        const srcPath = resolve(src, entry.name)
        const destPath = resolve(dest, entry.name)
        
        if (entry.isDirectory()) {
          fs.mkdirSync(destPath, { recursive: true })
          copyDir(srcPath, destPath)
        } else {
          fs.copyFileSync(srcPath, destPath)
        }
      }
    }
    
    copyDir(sourceDir, targetDir)
    console.log(`✅ Frontend assets copied to: ${targetDir}`)
    console.log(`📦 Access admin panel at: http://localhost:8080/`)
  }
})

// 根据模式返回不同配置
export default defineConfig(({ mode }) => {
  const isDev = mode === 'development'
  
  return {
    // 资源路径以根目录开头
    // 开发时访问 http://localhost:3000/
    // 生产部署后访问 http://localhost:8080/
    base: '/',
    
    plugins: [
      vue(),
      copyToServerPlugin()
    ],
    // 开发服务器配置
    server: {
      port: 3000,
      // 开发时代理到后端API
      proxy: {
        '/api': {
          target: 'http://localhost:8080',
          changeOrigin: true
        }
      }
    },
    // 构建配置
    build: {
      outDir: 'dist',
      assetsDir: 'assets'
    },
    // 开发时优化配置
    optimizeDeps: {
      include: ['vue', 'vue-router', 'pinia', 'element-plus', 'axios']
    }
  }
})
