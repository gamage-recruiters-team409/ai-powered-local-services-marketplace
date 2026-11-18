import { useState } from 'react';

import LoginPage from './LoginPage';
import RegisterPage from './RegisterPage';
import CheckEmailPage from './CheckEmailPage';
import VerifyEmailPage from './VerifyEmailPage';
import ForgotPasswordPage from './ForgotPasswordPage';
import ResetPasswordPage from './ResetPasswordPage';

const SCREENS = [
  {
    id: 'check-email',
    title: 'Check Your Email',
    category: 'Verification',
    component: <CheckEmailPage previewState="default" />,
  },
  {
    id: 'forgot-password-default',
    title: 'Forgot Password (Default)',
    category: 'Password Recovery',
    component: <ForgotPasswordPage previewState="default" />,
  },
  {
    id: 'forgot-password-invalid',
    title: 'Forgot Password (Invalid Email)',
    category: 'Password Recovery',
    component: <ForgotPasswordPage previewState="invalid-email" />,
  },
  {
    id: 'forgot-password-success',
    title: 'Forgot Password (Success)',
    category: 'Password Recovery',
    component: <ForgotPasswordPage previewState="success" />,
  },
  {
    id: 'login-default',
    title: 'Login (Default)',
    category: 'Authentication',
    component: <LoginPage previewState="default" />,
  },
  {
    id: 'login-error',
    title: 'Login (Error)',
    category: 'Authentication',
    component: <LoginPage previewState="error" />,
  },
  {
    id: 'login-not-verified',
    title: 'Login (Email Not Verified)',
    category: 'Authentication',
    component: <LoginPage previewState="email-not-verified" />,
  },
  {
    id: 'login-account-locked',
    title: 'Login (Account Locked)',
    category: 'Authentication',
    component: <LoginPage previewState="account-locked" />,
  },
  {
    id: 'register-default',
    title: 'Register (Default)',
    category: 'Onboarding',
    component: <RegisterPage previewState="default" />,
  },
  {
    id: 'register-error',
    title: 'Register (Error)',
    category: 'Onboarding',
    component: <RegisterPage previewState="error" />,
  },
  {
    id: 'reset-password-default',
    title: 'Reset Password (Default)',
    category: 'Password Recovery',
    component: <ResetPasswordPage previewState="default" />,
  },
  {
    id: 'reset-password-expired',
    title: 'Reset Password (Link Expired)',
    category: 'Password Recovery',
    component: <ResetPasswordPage previewState="expired" />,
  },
  {
    id: 'reset-password-success',
    title: 'Reset Password (Success)',
    category: 'Password Recovery',
    component: <ResetPasswordPage previewState="success" />,
  },
  {
    id: 'verify-email-invalid',
    title: 'Verify Email (Expired or Invalid)',
    category: 'Verification',
    component: <VerifyEmailPage previewState="invalid" />,
  },
  {
    id: 'verify-email-success',
    title: 'Verify Email (Success)',
    category: 'Verification',
    component: <VerifyEmailPage previewState="success" />,
  },
];

export default function AuthPreviewPage() {
  const [activeIdx, setActiveIdx] = useState(0);
  const [viewMode, setViewMode] = useState('single'); // 'single' | 'gallery'

  const activeScreen = SCREENS[activeIdx];

  const handlePrev = () => {
    setActiveIdx((prev) => (prev > 0 ? prev - 1 : SCREENS.length - 1));
  };

  const handleNext = () => {
    setActiveIdx((prev) => (prev < SCREENS.length - 1 ? prev + 1 : 0));
  };

  return (
    <div style={{ width: '100%', minHeight: '100vh', paddingBottom: '60px' }}>
      {/* Dev preview control bar */}
      <div
        style={{
          position: 'sticky',
          top: 0,
          zIndex: 100,
          backgroundColor: '#0f172a',
          color: '#ffffff',
          padding: '12px 24px',
          display: 'flex',
          flexWrap: 'wrap',
          alignItems: 'center',
          justifyContent: 'space-between',
          gap: '12px',
          boxShadow: '0 4px 12px rgba(0,0,0,0.15)',
        }}
      >
        <div style={{ display: 'flex', alignItems: 'center', gap: '12px' }}>
          <span
            style={{
              backgroundColor: '#0f766e',
              color: '#ffffff',
              padding: '2px 8px',
              borderRadius: '4px',
              fontSize: '11px',
              fontWeight: 700,
              letterSpacing: '0.5px',
            }}
          >
            DEV PREVIEW
          </span>
          <span style={{ fontSize: '14px', fontWeight: 600 }}>
            Auth Design System: 15 Figma Screens
          </span>
        </div>

        <div style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
          <button
            type="button"
            onClick={() => setViewMode(viewMode === 'single' ? 'gallery' : 'single')}
            style={{
              backgroundColor: '#1e293b',
              color: '#f8fafc',
              border: '1px solid #334155',
              borderRadius: '6px',
              padding: '6px 12px',
              fontSize: '13px',
              fontWeight: 500,
              cursor: 'pointer',
            }}
          >
            {viewMode === 'single' ? 'View All 15 (Gallery)' : 'Single Screen View'}
          </button>

          {viewMode === 'single' && (
            <div style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
              <button
                type="button"
                onClick={handlePrev}
                style={{
                  backgroundColor: '#1e293b',
                  color: '#ffffff',
                  border: '1px solid #334155',
                  borderRadius: '6px',
                  padding: '6px 10px',
                  fontSize: '13px',
                  cursor: 'pointer',
                }}
                aria-label="Previous screen"
              >
                ← Prev
              </button>

              <select
                value={activeIdx}
                onChange={(e) => setActiveIdx(Number(e.target.value))}
                style={{
                  backgroundColor: '#1e293b',
                  color: '#ffffff',
                  border: '1px solid #334155',
                  borderRadius: '6px',
                  padding: '6px 10px',
                  fontSize: '13px',
                  cursor: 'pointer',
                }}
              >
                {SCREENS.map((s, idx) => (
                  <option key={s.id} value={idx}>
                    {idx + 1}. {s.title}
                  </option>
                ))}
              </select>

              <button
                type="button"
                onClick={handleNext}
                style={{
                  backgroundColor: '#1e293b',
                  color: '#ffffff',
                  border: '1px solid #334155',
                  borderRadius: '6px',
                  padding: '6px 10px',
                  fontSize: '13px',
                  cursor: 'pointer',
                }}
                aria-label="Next screen"
              >
                Next →
              </button>
            </div>
          )}
        </div>
      </div>

      {/* Render Screen(s) */}
      {viewMode === 'single' ? (
        <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', padding: '32px 16px' }}>
          <div style={{ marginBottom: '16px', textAlign: 'center' }}>
            <span style={{ fontSize: '12px', color: '#64748b', fontWeight: 600, textTransform: 'uppercase', letterSpacing: '0.5px' }}>
              Screen {activeIdx + 1} of {SCREENS.length} • {activeScreen.category}
            </span>
            <h2 style={{ fontSize: '18px', fontWeight: 700, margin: '4px 0 0', color: '#0f172a' }}>
              {activeScreen.title}
            </h2>
          </div>
          <div style={{ width: '100%', display: 'flex', justifyContent: 'center' }}>
            {activeScreen.component}
          </div>
        </div>
      ) : (
        <div style={{ maxWidth: '900px', margin: '0 auto', padding: '32px 16px', display: 'flex', flexDirection: 'column', gap: '60px' }}>
          {SCREENS.map((s, idx) => (
            <section
              key={s.id}
              style={{
                display: 'flex',
                flexDirection: 'column',
                alignItems: 'center',
                borderBottom: idx < SCREENS.length - 1 ? '2px dashed #e2e8f0' : 'none',
                paddingBottom: '48px',
              }}
            >
              <div style={{ marginBottom: '20px', textAlign: 'center' }}>
                <span
                  style={{
                    backgroundColor: '#e0f2fe',
                    color: '#0369a1',
                    fontSize: '11px',
                    fontWeight: 700,
                    padding: '2px 8px',
                    borderRadius: '4px',
                    textTransform: 'uppercase',
                    letterSpacing: '0.5px',
                  }}
                >
                  Screen {String(idx + 1).padStart(2, '0')} / {SCREENS.length} • {s.category}
                </span>
                <h3 style={{ fontSize: '20px', fontWeight: 700, margin: '6px 0 0', color: '#0f172a' }}>
                  {s.title}
                </h3>
              </div>
              <div style={{ width: '100%', display: 'flex', justifyContent: 'center' }}>
                {s.component}
              </div>
            </section>
          ))}
        </div>
      )}
    </div>
  );
}
