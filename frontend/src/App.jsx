import { useEffect, useState } from 'react'
import './App.css'

function App() {
  const [backendStatus, setBackendStatus] = useState('checking...')

  useEffect(() => {
    fetch('/api/ping')
      .then((res) => res.json())
      .then((data) => setBackendStatus(data.status))
      .catch(() => setBackendStatus('unreachable'))
  }, [])

  return (
    <main>
      <h1>Fantasy App</h1>
      <p>Backend: {backendStatus}</p>
    </main>
  )
}

export default App
