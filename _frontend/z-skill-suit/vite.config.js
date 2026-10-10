import {defineConfig} from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
    plugins: [react()],
  resolve: { dedupe: ['react', 'react-dom', 'react-router-dom', 'antd', '@ant-design/icons', 'axios'] },
    server: {
        port: 3020,
        fs: {allow: ['..']},
        proxy: {'/skill': {target: 'http://localhost:8888', changeOrigin: true}, '/actuator': {target: 'http://localhost:8888', changeOrigin: true}},
    },
})
