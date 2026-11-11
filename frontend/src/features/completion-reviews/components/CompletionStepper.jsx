import CompletionIcon from './CompletionIcons'
import { STEPS, stepState } from '../utils/completionUtils'

/** Four-step progress: Confirmed → Started → Marked done → Completed. */
export default function CompletionStepper({ status }) {
  const { done, current, error } = stepState(status)

  return (
    <ol className="sc-stepper" aria-label="Service completion progress">
      {STEPS.map((label, index) => {
        const isError = error === index
        const isDone = !isError && index < done
        const isCurrent = !isDone && !isError && index === current
        const state = isError ? 'error' : isDone ? 'done' : isCurrent ? 'current' : 'todo'
        return (
          <li key={label} className={`sc-step sc-step--${state}`} aria-current={isCurrent ? 'step' : undefined}>
            <span className="sc-step__marker">
              {isDone && <CompletionIcon name="check" size={16} />}
              {isError && <CompletionIcon name="alert" size={16} />}
              {(isCurrent || state === 'todo') && index + 1}
            </span>
            <span className="sc-step__label">
              {label}
              {isDone && <span className="sc-visually-hidden"> (done)</span>}
              {isError && <span className="sc-visually-hidden"> (disputed)</span>}
            </span>
          </li>
        )
      })}
    </ol>
  )
}
