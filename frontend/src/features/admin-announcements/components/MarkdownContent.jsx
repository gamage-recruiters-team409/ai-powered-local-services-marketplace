import { renderMarkdown } from '../utils/markdown'
import styles from '../announcements.module.css'

/** Announcement message rendered safely from markdown (React elements only, never raw HTML). */
export function MarkdownContent({ text, className = '' }) {
  return <div className={`${styles.markdown} ${className}`.trim()}>{renderMarkdown(text)}</div>
}
