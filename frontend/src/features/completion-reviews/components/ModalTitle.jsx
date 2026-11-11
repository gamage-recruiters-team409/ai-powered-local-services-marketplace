import CompletionIcon from './CompletionIcons'

/** Modal heading with the rounded icon tile above the text (as in the Hi-Fi designs). */
export default function ModalTitle({ icon, children }) {
  return (
    <>
      <span className="sc-modal-icon">
        <CompletionIcon name={icon} size={24} />
      </span>
      <span className="sc-modal-title-text">{children}</span>
    </>
  )
}
