/**
 * Footer matching the approved LocalServe reference design.
 */
export function LocalServeFooter() {
  return (
    <footer className="localserve-footer">
      <div className="footer-container">
        <div className="footer-left">
          <span className="footer-brand">LocalServe</span>
          <nav className="footer-nav" aria-label="Footer links">
            <a href="#about" className="footer-link">About</a>
            <a href="#help" className="footer-link">Help Centre</a>
            <a href="#terms" className="footer-link">Terms</a>
            <a href="#privacy" className="footer-link">Privacy</a>
          </nav>
        </div>

        <div className="footer-right">
          <span>© 2026 LocalServe · Austin, TX</span>
        </div>
      </div>
    </footer>
  );
}

export default LocalServeFooter;

