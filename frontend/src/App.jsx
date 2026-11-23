import { RouterProvider } from 'react-router-dom'

import { AuthProvider } from './features/auth'
import { ToastProvider } from './shared/components/feedback'
import { router } from './routes/router'

function App() {
  return (
    <AuthProvider>
      <ToastProvider>
        <RouterProvider router={router} />
      </ToastProvider>
    </AuthProvider>
  )
}

export default App
