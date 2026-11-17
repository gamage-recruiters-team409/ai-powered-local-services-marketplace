import { checkPasswordCriteria } from '../utils/validators';

function CheckIcon() {
  return (
    <svg
      width="10"
      height="10"
      viewBox="0 0 12 12"
      fill="currentColor"
      aria-hidden="true"
    >
      <path d="M10 3L4.5 8.5 2 6" stroke="currentColor" strokeWidth="2" fill="none" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  );
}

const RULES = [
  { key: 'hasMinLength', label: 'At least 8 characters' },
  { key: 'hasUppercase', label: 'An uppercase letter' },
  { key: 'hasLowercase', label: 'A lowercase letter' },
  { key: 'hasDigit', label: 'A digit' },
];

export default function PasswordRules({ password = '' }) {
  const criteria = checkPasswordCriteria(password);

  return (
    <div className="auth-pw-rules" aria-label="Password requirements">
      <p className="auth-pw-rules__header">Password requirements:</p>
      <ul className="auth-pw-rules__list">
        {RULES.map((rule) => {
          const met = criteria[rule.key];
          return (
            <li
              key={rule.key}
              className={`auth-pw-rules__item${met ? ' auth-pw-rules__item--met' : ''}`}
            >
              <span className="auth-pw-rules__indicator" aria-hidden="true">
                {met && <CheckIcon />}
              </span>
              {rule.label}
            </li>
          );
        })}
      </ul>
    </div>
  );
}
