import { useState } from 'react'

import { Button, Modal } from '../../../shared/components/forms'
import CompletionIcon from './CompletionIcons'
import ModalTitle from './ModalTitle'
import PhotoThumb from './PhotoThumb'
import { formatDateTime, formatShortDate, payoutReleaseDate } from '../utils/completionUtils'

/** S03 – read-only completion details: note, photos and completion timeline. */
export default function CompletionDetailsModal({ isOpen, onClose, booking, completion }) {
  const [selected, setSelected] = useState(0)
  const photos = completion?.photoUrls ?? []
  const photoCount = photos.length
  const release = payoutReleaseDate(completion?.confirmedAt)

  const markedMeta = [
    completion?.completionNote ? 'note' : null,
    photoCount ? `${photoCount} photo${photoCount === 1 ? '' : 's'}` : null,
  ].filter(Boolean).join(' + ')

  const events = [
    completion?.startedAt && {
      icon: 'play', tone: 'indigo', title: 'Service started',
      meta: `${formatDateTime(completion.startedAt)}${booking.providerName ? ` · by ${booking.providerName}` : ''}`,
    },
    completion?.markedCompletedAt && {
      icon: 'checkCircle', tone: 'teal', title: 'Marked as completed',
      meta: `${formatDateTime(completion.markedCompletedAt)}${markedMeta ? ` · ${markedMeta}` : ''}`,
    },
    completion?.confirmedAt && {
      icon: 'user', tone: 'green',
      title: completion.confirmationType === 'AUTO' ? 'Confirmed automatically' : 'Confirmed by customer',
      meta: completion.confirmationType === 'AUTO'
        ? `${formatDateTime(completion.confirmedAt)} · after 48 hours`
        : `${formatDateTime(completion.confirmedAt)}${booking.customerName ? ` · ${booking.customerName}` : ''}`,
    },
    release && {
      icon: 'wallet', tone: 'blue', title: 'Payout release',
      meta: `${formatShortDate(release)} · if no dispute is raised`,
    },
  ].filter(Boolean)

  return (
    <Modal
      isOpen={isOpen}
      onClose={onClose}
      className="sc-modal sc-modal--wide"
      title={<ModalTitle icon="checkCircle">Completion details</ModalTitle>}
      description={[`Booking #${booking.ref}`, booking.title, booking.customerName].filter(Boolean).join(' · ')}
      footer={<Button variant="outline" onClick={onClose}>Close</Button>}
    >
      <div className="sc-details">
        <div className="sc-details__main">
          <h3 className="sc-subheading">Completion note</h3>
          <blockquote className="sc-note-box">
            {completion?.completionNote ? `“${completion.completionNote}”` : 'No note was added.'}
          </blockquote>

          <h3 className="sc-subheading">
            Photos{' '}
            <span className="sc-field-label__hint">
              ({photoCount}{photoCount ? ' · click to enlarge' : ''})
            </span>
          </h3>
          {photoCount > 0 ? (
            <>
              <div className="sc-photo-viewer">
                <PhotoThumb key={photos[selected]} url={photos[selected]} index={selected} size="large" />
              </div>
              <div className="sc-photo-grid">
                {photos.map((url, index) => (
                  <button
                    key={url}
                    type="button"
                    className={`sc-photo-select${index === selected ? ' sc-photo-select--active' : ''}`}
                    onClick={() => setSelected(index)}
                    aria-label={`Show photo ${index + 1}`}
                    aria-pressed={index === selected}
                  >
                    <PhotoThumb url={url} index={index} size="small" />
                  </button>
                ))}
              </div>
            </>
          ) : (
            <p className="sc-muted">No photos were added.</p>
          )}
        </div>

        <div className="sc-details__side">
          <h3 className="sc-subheading">Completion timeline</h3>
          <ol className="sc-events">
            {events.map((event) => (
              <li key={event.title} className="sc-event">
                <span className={`sc-event__icon sc-event__icon--${event.tone}`}>
                  <CompletionIcon name={event.icon} size={20} />
                </span>
                <span>
                  <span className="sc-event__title">{event.title}</span>
                  <span className="sc-event__meta">{event.meta}</span>
                </span>
              </li>
            ))}
          </ol>
          <p className="sc-callout sc-callout--info">
            <CompletionIcon name="info" />
            <span>These details are kept with the booking and are used as evidence if a dispute is raised.</span>
          </p>
        </div>
      </div>
    </Modal>
  )
}
