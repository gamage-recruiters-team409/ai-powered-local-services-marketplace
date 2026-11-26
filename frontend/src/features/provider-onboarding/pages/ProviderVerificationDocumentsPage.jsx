import { useEffect, useMemo, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { ProviderPage } from '../../../shared/layouts/provider'
import { Button, Input, Modal } from '../../../shared/components/forms'
import { InlineMessage, PageError, PageLoader, useToast } from '../../../shared/components/feedback'
import {
  getMyProviderProfile,
  getMyProviderVerificationStatus,
  getMyVerificationDocuments,
  submitMyProviderVerification,
  uploadMyVerificationDocument,
} from '../services/providerProfileApi'
import {
  DOCUMENT_LABELS,
  EDITABLE_VERIFICATION_STATUSES,
  VERIFICATION_STATUS_LABELS,
  colomboDateString,
  displayDocuments,
  documentsUploadedAfter,
  formatDocumentDate,
  isDocumentCurrent,
  isDocumentExpired,
  newestDocumentOfType,
  requiredDocumentTypes,
  sortUploadedDocuments,
  verificationUploadCutoff,
} from '../services/providerVerificationUiUtils'
import ProviderVerificationStatusPanel from '../components/ProviderVerificationStatusPanel'
import ProviderFeatureIcon from '../components/ProviderFeatureIcon'
import styles from '../providerVerificationDocuments.module.css'

// Match the existing WP02 backend, which accepts PDF/JPG/PNG files up to 10 MB.
const MAX_BYTES = 10 * 1024 * 1024
const ALLOWED_TYPES = new Set(['application/pdf', 'image/jpeg', 'image/png'])

function DocumentCard({ title, detail, document, number, required, expired, disabled, onUpload }) {
  return (
    <article className={styles.documentCard}>
      <span className={styles.docIcon} aria-hidden="true">
        <ProviderFeatureIcon name="document" size={23} />
      </span>
      <div className={styles.documentBody}>
        <h3>{number}. {title} {required && <span className={styles.required}>*</span>}</h3>
        <p>{detail}</p>
        <small>Accepted formats: PDF, JPG, PNG | Maximum size: 10 MB</small>
        {document && (
          <p className={`${styles.uploadedDocument} ${expired ? styles.expiredDocument : ''}`}>
            {expired ? 'Expired: ' : 'Uploaded: '}{document.originalFileName || 'Document'}
            {document.expiryDate ? ` · Expiry ${formatDocumentDate(document.expiryDate)}` : ''}
          </p>
        )}
      </div>
      <Button variant="outline" disabled={disabled} onClick={onUpload}>
        {expired ? 'Upload replacement' : document ? 'Upload new file' : 'Upload file'}
      </Button>
    </article>
  )
}

function UploadModal({
  uploadType,
  uploadFile,
  issueDate,
  expiryDate,
  uploading,
  uploadError,
  inputRef,
  onClose,
  onUpload,
  onFileChange,
  onIssueDateChange,
  onExpiryDateChange,
}) {
  return (
    <Modal
      isOpen={Boolean(uploadType)}
      onClose={onClose}
      closeOnBackdrop={!uploading}
      title={`Upload ${DOCUMENT_LABELS[uploadType]?.toLowerCase() || 'document'}`}
      className={styles.uploadModal}
    >
      <form className={styles.uploadForm} onSubmit={onUpload}>
        <p>Choose a valid document for your provider verification. Files are stored privately.</p>
        <div
          className={styles.dropzone}
          onDragOver={(event) => event.preventDefault()}
          onDrop={(event) => {
            event.preventDefault()
            if (!uploading) onFileChange(event.dataTransfer.files?.[0] || null)
          }}
        >
          <span className={styles.dropIcon} aria-hidden="true"><ProviderFeatureIcon name="upload" size={27} /></span>
          <strong>Drag and drop your file here</strong>
          <span>or</span>
          <Button variant="outline" disabled={uploading} onClick={() => inputRef.current?.click()}>Browse files</Button>
          <small>PDF, JPG or PNG · Maximum 10 MB</small>
          <input
            ref={inputRef}
            type="file"
            accept=".pdf,.jpg,.jpeg,.png,application/pdf,image/jpeg,image/png"
            className={styles.hiddenFile}
            aria-label="Choose verification document"
            disabled={uploading}
            onChange={(event) => onFileChange(event.target.files?.[0] || null)}
          />
        </div>
        {uploadFile && (
          <div className={styles.filePreview}>
            <ProviderFeatureIcon name="document" size={22} />
            <div><strong>{uploadFile.name}</strong><small>{(uploadFile.size / 1024 / 1024).toFixed(2)} MB</small></div>
            <Button variant="outline" disabled={uploading} onClick={() => onFileChange(null)}>Remove</Button>
          </div>
        )}
        <div className={styles.dateGrid}>
          <Input label="Issue date (optional)" type="date" value={issueDate} onChange={(event) => onIssueDateChange(event.target.value)} disabled={uploading} />
          <Input label="Expiry date (optional)" type="date" value={expiryDate} onChange={(event) => onExpiryDateChange(event.target.value)} disabled={uploading} />
        </div>
        {uploadError && <InlineMessage variant="error">{uploadError}</InlineMessage>}
        <div className={styles.modalActions}>
          <Button variant="outline" onClick={onClose} disabled={uploading}>Cancel</Button>
          <Button type="submit" loading={uploading} disabled={!uploadFile || uploading}>Upload</Button>
        </div>
      </form>
    </Modal>
  )
}

export default function ProviderVerificationDocumentsPage() {
  const navigate = useNavigate()
  const toast = useToast()
  const fileInputRef = useRef(null)

  const [loading, setLoading] = useState(true)
  const [reloadKey, setReloadKey] = useState(0)
  const [error, setError] = useState('')
  const [profile, setProfile] = useState(null)
  const [verification, setVerification] = useState(null)
  const [documents, setDocuments] = useState([])
  const [today, setToday] = useState('')
  const [editingDocuments, setEditingDocuments] = useState(false)
  const [uploadType, setUploadType] = useState(null)
  const [uploadFile, setUploadFile] = useState(null)
  const [issueDate, setIssueDate] = useState('')
  const [expiryDate, setExpiryDate] = useState('')
  const [uploadError, setUploadError] = useState('')
  const [uploading, setUploading] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  const [submitError, setSubmitError] = useState('')

  useEffect(() => {
    let active = true
    async function load() {
      try {
        const profileResult = await getMyProviderProfile().catch((failure) => {
          if (failure?.status === 404 || failure?.response?.status === 404) return null
          throw failure
        })
        if (!active) return
        if (!profileResult) {
          setProfile(null)
          setVerification(null)
          setDocuments([])
          setError('')
          return
        }
        const [status, documentList] = await Promise.all([
          getMyProviderVerificationStatus(),
          getMyVerificationDocuments(),
        ])
        if (!active) return
        setProfile(profileResult)
        setVerification(status)
        setDocuments(sortUploadedDocuments(documentList))
        setToday(colomboDateString(new Date()))
        setError('')
      } catch (failure) {
        if (active) setError(failure.message || 'Unable to load verification details.')
      } finally {
        if (active) setLoading(false)
      }
    }
    load()
    return () => { active = false }
  }, [reloadKey])

  const shownDocuments = useMemo(() => displayDocuments(documents), [documents])
  const status = verification?.status || 'NOT_SUBMITTED'
  const canEdit = EDITABLE_VERIFICATION_STATUSES.includes(status)
  const requiresUpdates = ['REJECTED', 'RESUBMISSION_REQUESTED', 'EXPIRED'].includes(status)
  const categoryReverification = status === 'RESUBMISSION_REQUESTED' && /categor(y|ies)/i.test(verification?.decisionReason || '')
  const showReverification = editingDocuments && (status === 'EXPIRED' || categoryReverification)
  const neededTypes = requiredDocumentTypes(profile?.providerType)
  const requiredReady = neededTypes.every((type) => isDocumentCurrent(newestDocumentOfType(documents, type), today))
  // Old upload records may remain on the server; assess the newest upload per type.
  const typesPresent = [...new Set(documents.map((document) => document.documentType))]
  const newestByTypeValid = typesPresent.every((type) => isDocumentCurrent(newestDocumentOfType(documents, type), today))
  const previousCutoff = verificationUploadCutoff(verification)
  const hasUpdatedUpload = documentsUploadedAfter(documents, previousCutoff)
  const canSubmit = canEdit && requiredReady && newestByTypeValid
    && (!requiresUpdates || hasUpdatedUpload) && !uploading && !submitting
  const expiredDocuments = shownDocuments.filter((document) => isDocumentExpired(document, today))
  const nonExpiredDocuments = shownDocuments.filter((document) => !isDocumentExpired(document, today))

  function openUploader(type) {
    if (!canEdit || uploading || submitting) return
    setUploadType(type)
    setUploadFile(null)
    setIssueDate('')
    setExpiryDate('')
    setUploadError('')
    if (fileInputRef.current) fileInputRef.current.value = ''
  }

  function closeUploader() {
    if (uploading) return
    setUploadType(null)
    setUploadFile(null)
    setUploadError('')
    if (fileInputRef.current) fileInputRef.current.value = ''
  }

  function chooseFile(file) {
    setUploadFile(file)
    setUploadError('')
    if (!file && fileInputRef.current) fileInputRef.current.value = ''
  }

  async function handleUpload(event) {
    event.preventDefault()
    if (!uploadType || !uploadFile) {
      setUploadError('Choose a document to upload.')
      return
    }
    if (!ALLOWED_TYPES.has(uploadFile.type)) {
      setUploadError('Only PDF, JPG and PNG files are supported.')
      return
    }
    if (uploadFile.size > MAX_BYTES || uploadFile.size === 0) {
      setUploadError('The document must be nonempty and no larger than 10 MB.')
      return
    }
    if (issueDate && expiryDate && expiryDate < issueDate) {
      setUploadError('Expiry date cannot be earlier than the issue date.')
      return
    }
    setUploading(true)
    setUploadError('')
    try {
      const savedDocument = await uploadMyVerificationDocument({ documentType: uploadType, file: uploadFile, issueDate, expiryDate })
      // The POST has succeeded. Reflect it even if a subsequent GET fails.
      if (savedDocument) {
        setDocuments((current) => sortUploadedDocuments([savedDocument, ...current]))
      }
      try {
        const latest = await getMyVerificationDocuments()
        setDocuments(sortUploadedDocuments(latest))
      } catch {
        // The upload itself succeeded; the next load can refresh metadata.
      }
      setToday(colomboDateString(new Date()))
      setUploadType(null)
      setUploadFile(null)
      setIssueDate('')
      setExpiryDate('')
      setSubmitError('')
      toast.success('Verification document uploaded successfully.')
    } catch (failure) {
      setUploadError(failure.message || 'Unable to upload your document.')
    } finally {
      setUploading(false)
    }
  }

  async function submitVerification() {
    if (!canSubmit) return
    setSubmitting(true)
    setSubmitError('')
    try {
      const updated = await submitMyProviderVerification()
      setVerification(updated)
      setEditingDocuments(false)
      toast.success('Verification submitted for review.')
      // A refresh failure must never turn a successful submission into an error.
      try {
        const latest = await getMyVerificationDocuments()
        setDocuments(sortUploadedDocuments(latest))
      } catch {
        // Previously loaded documents remain visible; the next page load retries.
      }
    } catch (failure) {
      setSubmitError(failure.message || 'Unable to submit. Review your documents and try again.')
    } finally {
      setSubmitting(false)
    }
  }

  if (loading) return (
    <ProviderPage title="Verification documents" showBack={false} plain>
      <PageLoader label="Loading verification details..." />
    </ProviderPage>
  )
  if (error) return (
    <ProviderPage title="Verification documents" showBack={false} plain>
      <PageError title="Verification unavailable" message={error} onRetry={() => { setLoading(true); setReloadKey((value) => value + 1) }} />
    </ProviderPage>
  )
  if (!profile) return (
    <ProviderPage title="Submit verification documents" subtitle="Complete your provider profile before submitting documents." showBack={false} plain>
      <div className={styles.emptyProfile}>
        <p>Your provider profile has not been created yet.</p>
        <Button onClick={() => navigate('/provider/profile')}>Complete provider profile</Button>
      </div>
    </ProviderPage>
  )

  if (status !== 'NOT_SUBMITTED' && !editingDocuments) return (
    <div className={styles.pageShell}>
      <div className={styles.breadcrumb}>Provider <span>/</span> Verification <span>/</span> Status</div>
      <ProviderPage title="Verification Status" subtitle="Track the progress of your provider verification." showBack={false} plain>
        <ProviderVerificationStatusPanel
          verification={verification}
          documents={documents}
          asOfDate={today}
          onViewProfile={() => navigate('/provider/profile')}
          onEditDocuments={() => { if (canEdit) { setSubmitError(''); setEditingDocuments(true) } }}
        />
      </ProviderPage>
    </div>
  )

  return (
    <div className={styles.pageShell}>
      <div className={styles.breadcrumb}>
        Provider <span>/</span> {showReverification ? 'Verification / Re-verification' : 'My Profile / Documents'}
      </div>
      <ProviderPage
        title={showReverification ? 'Re-verification required' : 'Submit verification documents'}
        subtitle={showReverification ? 'Upload updated documents to request another verification review.' : 'Upload the required documents so your provider profile can be reviewed.'}
        showBack={false}
        plain
      >
        <div className={styles.content}>
          {!showReverification && (
            <div className={styles.progress} aria-label="Provider setup progress">
              <div className={styles.stepDone}><span>✓</span><strong>Provider profile</strong></div>
              <div className={styles.progressLine} aria-hidden="true" />
              <div className={styles.stepCurrent}><span>2</span><strong>Verification documents</strong></div>
            </div>
          )}

          {showReverification ? (
            <>
              <div className={styles.warningBanner}>
                <ProviderFeatureIcon name="alert" size={23} />
                <div>
                  <strong>Why is re-verification required?</strong>
                  <p>{status === 'EXPIRED'
                    ? 'Your previous verification is marked expired. Check the document dates below and upload valid supporting evidence.'
                    : verification?.decisionReason || 'Your selected service categories require updated verification evidence.'}</p>
                </div>
              </div>
              {status === 'EXPIRED' && <section className={`${styles.section} ${styles.boxedSection}`}>
                <h2>Documents requiring replacement</h2>
                <p>Only documents actually uploaded with a recorded expired date are listed.</p>
                <div className={styles.docList}>
                  {expiredDocuments.length ? expiredDocuments.map((document, index) => (
                    <DocumentCard
                      key={document.id || `expired-${index}`}
                      title={DOCUMENT_LABELS[document.documentType] || 'Verification document'}
                      detail={`Recorded expiry: ${formatDocumentDate(document.expiryDate)}`}
                      document={document}
                      number={index + 1}
                      expired
                      disabled={!canEdit || uploading || submitting}
                      onUpload={() => openUploader(document.documentType)}
                    />
                  )) : <p className={styles.hint}>No expired document can be identified from the available metadata. You can upload updated evidence using the controls below.</p>}
                </div>
              </section>}
              {status === 'EXPIRED' && nonExpiredDocuments.length > 0 && (
                <section className={`${styles.section} ${styles.boxedSection}`}>
                  <h2>Documents not expired by recorded date</h2>
                  <p>These uploaded documents do not have an expired date recorded.</p>
                  <div className={styles.docList}>
                    {nonExpiredDocuments.map((document, index) => (
                      <div className={styles.savedDocument} key={document.id || `saved-${index}`}>
                        <span aria-hidden="true"><ProviderFeatureIcon name="document" size={21} /></span>
                        <div><strong>{DOCUMENT_LABELS[document.documentType] || 'Verification document'}</strong><small>{document.originalFileName || 'Filename unavailable'}</small></div>
                        <span className={styles.savedBadge}>{document.expiryDate ? 'VALID BY DATE' : 'DATE NOT RECORDED'}</span>
                      </div>
                    ))}
                  </div>
                </section>
              )}
              <section className={`${styles.section} ${styles.boxedSection}`}>
                <h2>{categoryReverification ? 'Documents for your updated services' : 'Upload updated documents'}</h2>
                <p>Upload a replacement or additional evidence before submitting re-verification.</p>
                <div className={styles.quickUploads}>
                  <Button variant="outline" disabled={uploading || submitting} onClick={() => openUploader('ID')}>Upload identity document</Button>
                  {profile.providerType === 'BUSINESS' && <Button variant="outline" disabled={uploading || submitting} onClick={() => openUploader('BUSINESS')}>Upload business registration</Button>}
                  <Button variant="outline" disabled={uploading || submitting} onClick={() => openUploader('CERTIFICATE')}>Upload certificate</Button>
                </div>
              </section>
            </>
          ) : (
            <section className={styles.section} aria-labelledby="verification-documents-title">
              <h2 id="verification-documents-title">Verification documents</h2>
              <p>Required documents depend on whether you have an individual or business provider profile.</p>
              <div className={styles.notice}>ⓘ Verification documents are private and accessible only through authorised services.</div>
              {requiresUpdates && (
                <div className={styles.statusLine}>
                  <strong>Current status: {VERIFICATION_STATUS_LABELS[status] || status}</strong>
                  {verification?.decisionReason && <p>Reason: {verification.decisionReason}</p>}
                  {!verification?.decisionReason && <p>Review the requested changes and upload updated evidence.</p>}
                </div>
              )}
              <div className={styles.docList}>
                <DocumentCard
                  number={1}
                  title="Identity document"
                  detail="Upload a valid government-issued identity document."
                  document={newestDocumentOfType(documents, 'ID')}
                  required
                  expired={isDocumentExpired(newestDocumentOfType(documents, 'ID'), today)}
                  disabled={!canEdit || uploading || submitting}
                  onUpload={() => openUploader('ID')}
                />
                {profile.providerType === 'BUSINESS' && (
                  <DocumentCard
                    number={2}
                    title="Business registration"
                    detail="Required for business provider profiles."
                    document={newestDocumentOfType(documents, 'BUSINESS')}
                    required
                    expired={isDocumentExpired(newestDocumentOfType(documents, 'BUSINESS'), today)}
                    disabled={!canEdit || uploading || submitting}
                    onUpload={() => openUploader('BUSINESS')}
                  />
                )}
                <DocumentCard
                  number={profile.providerType === 'BUSINESS' ? 3 : 2}
                  title="Certificate / qualification"
                  detail="Provide supporting certificates if your service categories require them."
                  document={newestDocumentOfType(documents, 'CERTIFICATE')}
                  expired={isDocumentExpired(newestDocumentOfType(documents, 'CERTIFICATE'), today)}
                  disabled={!canEdit || uploading || submitting}
                  onUpload={() => openUploader('CERTIFICATE')}
                />
              </div>
              {canEdit && <div className={styles.addCertificate}><Button variant="outline" disabled={uploading || submitting} onClick={() => openUploader('CERTIFICATE')}>+ Add another certificate</Button></div>}
            </section>
          )}

          {submitError && <InlineMessage variant="error">{submitError}</InlineMessage>}
          <div className={styles.actions}>
            <Button variant="outline" onClick={() => editingDocuments ? setEditingDocuments(false) : navigate('/provider/profile')}>← Back</Button>
            <div className={styles.rightActions}>
              <Button variant="outline" onClick={() => toast.success(documents.length ? 'Your uploaded documents are saved. Submit when you are ready.' : 'You can upload your documents when ready.')}>Save draft</Button>
              <Button loading={submitting} disabled={!canSubmit} onClick={submitVerification}>
                {showReverification ? 'Submit re-verification' : requiresUpdates ? 'Resubmit verification' : 'Submit for review'}
              </Button>
            </div>
          </div>
          {!requiredReady && <p className={styles.hint}>Upload the required identity and, where applicable, business registration documents before submitting.</p>}
          {requiredReady && !newestByTypeValid && <p className={styles.hint}>Replace your most recently uploaded expired documents before submitting.</p>}
          {requiresUpdates && !hasUpdatedUpload && <p className={styles.hint}>At least one document must be uploaded after the previous verification decision before resubmitting.</p>}
        </div>
      </ProviderPage>

      <UploadModal
        uploadType={uploadType}
        uploadFile={uploadFile}
        issueDate={issueDate}
        expiryDate={expiryDate}
        uploadError={uploadError}
        uploading={uploading}
        inputRef={fileInputRef}
        onClose={closeUploader}
        onUpload={handleUpload}
        onFileChange={chooseFile}
        onIssueDateChange={setIssueDate}
        onExpiryDateChange={setExpiryDate}
      />
    </div>
  )
}
