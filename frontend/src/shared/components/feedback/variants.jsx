import {
  AlertTriangleIcon,
  CheckCircleIcon,
  InfoIcon,
  MessageIcon,
  XCircleIcon,
} from './icons.jsx'

export const VARIANT_ICONS = {
  success: CheckCircleIcon,
  error: XCircleIcon,
  warning: AlertTriangleIcon,
  info: InfoIcon,
  message: MessageIcon,
}

export function VariantIcon({ variant, className }) {
  const Icon = VARIANT_ICONS[variant] ?? InfoIcon
  return <Icon className={className} />
}
