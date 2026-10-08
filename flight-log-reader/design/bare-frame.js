// Passthrough frame used when the app runs full-screen (Android WebView / narrow screens).
window.BareFrame = function BareFrame(props) {
  return React.createElement('div', { style: { width: '100vw', height: '100dvh', overflow: 'hidden', display: 'flex', flexDirection: 'column' } },
    React.createElement('div', { style: { flex: 1, minHeight: 0, overflow: 'hidden' } }, props.children));
};
