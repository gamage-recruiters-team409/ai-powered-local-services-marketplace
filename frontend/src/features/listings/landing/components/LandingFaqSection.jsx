import { useRef, useState } from 'react'
import { PUBLIC_BRAND } from '../../../../shared/layouts/public/publicBrand'
import { LANDING_FAQ_ITEMS } from '../data/landingContent'
import { useInViewOnce } from '../hooks/useSpringScrollProgress'
import { ArrowRightIcon, EditorialBurstAccent, MailIcon } from './LandingIcons'
import styles from '../styles/publicLanding.module.css'

export function LandingFaqSection() {
  const sectionRef = useRef(null)
  const inView = useInViewOnce(sectionRef, 0.14)
  const [openId, setOpenId] = useState(LANDING_FAQ_ITEMS[0]?.id ?? null)

  const handleToggle = (id) => {
    setOpenId((prev) => (prev === id ? null : id))
  }

  return (
    <section
      id="landing-faq"
      ref={sectionRef}
      className={`${styles.faqSection} ${inView ? styles.sectionRevealed : ''}`}
      aria-labelledby="landing-faq-heading"
    >
      <div className={styles.sectionContainer}>
        <div className={styles.faqGrid}>
          {/* Left Column (Q4 Option B: Headline + Contact Support Button Only, Zero Input Box) */}
          <div className={styles.faqIntroCol}>
            <span className={styles.sectionPill}>Common Questions</span>
            <h2 id="landing-faq-heading" className={styles.faqHeading}>
              Got A Question For{' '}
              <span className={styles.faqHeadingAccent}>LocalServe?</span>
              <EditorialBurstAccent size={24} className={styles.editorialBurstInline} />
            </h2>
            <p className={styles.faqLead}>
              Everything you need to know about our escrow protection, 48-hour completion review,
              72-hour dispute mediation, transparent commissions, and manual Admin verification.
            </p>

            <div className={styles.faqSupportCard}>
              <a
                href={`mailto:${PUBLIC_BRAND.supportEmail}?subject=LocalServe%20Sri%20Lanka%20Inquiry`}
                className={styles.faqSupportBtn}
              >
                <MailIcon size={16} />
                <span>Contact Support</span>
                <ArrowRightIcon size={15} className={styles.faqSupportArrow} />
              </a>
              <p className={styles.faqSupportNote}>
                <strong>{PUBLIC_BRAND.supportEmail}</strong> · {PUBLIC_BRAND.supportHours}
              </p>
            </div>
          </div>

          {/* Right Column: Hairline-Divided Expandable FAQ Accordion */}
          <div className={styles.faqListCol}>
            <p className={styles.faqListIntro}>
              Maybe your question has already been answered — check this out.
            </p>

            <div className={styles.faqAccordion}>
              {LANDING_FAQ_ITEMS.map((item) => {
                const isOpen = openId === item.id
                const panelId = `${item.id}-panel`
                return (
                  <div
                    key={item.id}
                    className={`${styles.faqItem} ${isOpen ? styles.faqItemOpen : ''}`}
                  >
                    <h3 className={styles.faqItemHeading}>
                      <button
                        type="button"
                        className={styles.faqTrigger}
                        aria-expanded={isOpen}
                        aria-controls={panelId}
                        onClick={() => handleToggle(item.id)}
                      >
                        <span className={styles.faqQuestionText}>{item.question}</span>
                        <span className={styles.faqArrowBadge} aria-hidden="true">
                          <ArrowRightIcon
                            size={16}
                            className={`${styles.faqArrowIcon} ${
                              isOpen ? styles.faqArrowIconOpen : ''
                            }`}
                          />
                        </span>
                      </button>
                    </h3>

                    <div
                      id={panelId}
                      role="region"
                      className={`${styles.faqBody} ${isOpen ? styles.faqBodyOpen : ''}`}
                    >
                      <div className={styles.faqBodyInner}>
                        <p className={styles.faqAnswer}>{item.answer}</p>
                      </div>
                    </div>
                  </div>
                )
              })}
            </div>
          </div>
        </div>
      </div>
    </section>
  )
}
