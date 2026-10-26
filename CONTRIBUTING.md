# Contributing Guidelines

## Branch Workflow

Normal development must follow:

develop
-> create a task branch
-> complete assigned work
-> commit
-> push branch
-> open pull request into develop
-> review
-> corrections if required
-> approval
-> squash merge

Do not push directly to:

- main
- develop

## Branch Naming

Approved prefixes:

- feature/
- fix/
- refactor/
- test/
- docs/
- chore/

Examples:

- feature/auth-login
- feature/provider-verification
- feature/booking-calendar
- fix/payment-validation
- docs/api-contract

Avoid names such as:

- my-work
- final
- changes
- completed
- branch1

## Commit Messages

Use:

type(scope): short description

Examples:

- feat(auth): add customer login
- feat(booking): implement availability validation
- fix(payment): correct payment status handling
- docs(api): update booking API contract

## Pull Requests

Normal pull requests must target:

develop

Each pull request must clearly describe:

- Summary
- Related task
- Main changes
- Development verification
- Evidence
- Dependencies
- Shared model/API/component impact
- Known issues
- Security considerations

## Shared Work

Do not silently modify:

- shared data models
- API contracts
- status values
- shared constants
- authentication/security configuration
- reusable layouts
- shared UI components
- integration configuration

Coordinate with the relevant owner and Team Lead before making major shared changes.

## Security

Never commit:

- .env files
- passwords
- API keys
- JWT secrets
- MongoDB credentials
- payment credentials
- AI provider credentials
- private tokens
- private user documents
- production data

Use `.env.example` files for variable names only.

## Review

Developers must not merge their own pull requests.

Corrections requested during review must be completed on the same branch unless otherwise instructed.


Normal development pull requests must target the develop branch.
