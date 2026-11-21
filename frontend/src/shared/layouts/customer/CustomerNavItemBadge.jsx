/** Renders the optional `badge` component of a nav config item (e.g. an unread count from WP06). */
export function CustomerNavItemBadge({ item }) {
  const Badge = item?.badge

  if (!Badge) {
    return null
  }

  return <Badge />
}
