import { render } from 'preact';
import { App } from './app/App';
import './styles.css';

render(<App />, document.getElementById('app')!);

// Installable, and the app shell plus the engine keep working offline once loaded.
if ('serviceWorker' in navigator && import.meta.env.PROD) {
  navigator.serviceWorker.register('/sw.js', { scope: '/' }).catch(() => undefined);
}
