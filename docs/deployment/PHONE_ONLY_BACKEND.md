# Phone-only backend decision memo

Checked on 2026-11-06. No account was created, no credentials were submitted, no purchase was made, and no order was placed.

## Decision required

The phone-only goal is technically possible only if an always-on hosted service runs the backend and MetaApi hosts a connected MT5 terminal. Android cannot replace the 24/7 MT5 terminal requirement for FBS, Exness, HFM, or Deriv MT5.

The recommended path is a paid Render service for the existing containerised Python bridge, subject to the owner's written approval and confirmation that the owner's Nigerian payment method is accepted. MetaApi remains a separate paid dependency. The existing Windows worker and EA path should remain available as the alternative connector.

Do not spend money or enter broker credentials until the owner approves the recommendation and the security model.

## MetaApi terms and fit

MetaApi's current pricing page lists a Regular subscription at **$30 per month** and an Extended subscription at **$100 per month**, excluding VAT/GST. Both list a one-time 7-day trial, a **$5 signup bonus**, and **one free MT account included**. API access is billed on a pay-as-you-go basis on top of the subscription. The page currently says market-data API pricing is temporarily unavailable and asks customers to contact support for that pricing. [1]

The current client documentation says MetaApi is a paid service. It provisions a cloud API server after an MT account is added and exposes REST and WebSocket APIs. The documented features include account information, positions, orders, history, quotes, candles, symbol specifications, margin calculation, and trade execution. The documentation recommends WebSocket for automated trading because it provides real-time terminal-state synchronisation. [2]

I did not find an official current page confirming a two-account free tier. The current pricing page says one free MT account is included. The technical documentation also describes MetaApi as paid. The older or conflicting account-count claim must therefore be treated as unverified until MetaApi confirms it in writing. [1] [2]

The existing DestinedFX MT5 EA cannot simply be uploaded to MetaApi. MetaApi's official Expert Advisor endpoint says custom EAs can be created for **MT4 G1 instances only**. The new MetaApi connector would therefore need server-side enforcement of the existing risk and execution gates. The MT5 EA safety logic would not be the safety boundary on this path. [3]

MetaApi holds the broker terminal password so it can connect to the account. Its security FAQ states that adding an account exposes the MT account password and API calls to the service. It recommends using an investor, read-only password where appropriate. The implementation must prefer an investor password for read-only verification and must require separate informed approval before any master password is sent. [4]

## Hosting options for the Python backend

### Option A: Render

Render's current pricing page lists a Hobby workspace at $0 per month plus compute. Its Free compute plan is $0 per month with 512 MB RAM. A paid service with less than one CPU and 512 MB RAM is **$7 per month**. A 1 CPU, 2 GB service is **$25 per month**. Render supports custom Docker containers, web services, and background workers. [5]

The free plan is not suitable for unattended trading. Render spins down a free web service after 15 minutes without inbound traffic, takes about one minute to start again, loses its local filesystem on restart or spin-down, and suspends free services after the workspace's 750 free instance hours are used. Render explicitly says free instances are not for production applications. [6]

A paid 512 MB service is the lowest listed paid option and should be treated as the minimum starting size, not a capacity guarantee. The backend would need durable external state for order intents, leases, idempotency keys, and reconciliation records. A persistent database is a separate cost and is not included in the $7 compute figure.

Render says it accepts all major credit and debit cards, with payment information processed by Stripe. That establishes the payment methods Render accepts, but it does not prove that a particular Nigerian-issued card or Nigerian signup will pass provider checks. The owner's country and card must be confirmed during a later owner-controlled signup. No signup was attempted. [5]

### Option B: Fly.io

Fly.io's current pricing page has no monthly platform fee. In the Ashburn region, a continuously running `shared-cpu-1x` Machine with 256 MB RAM is listed at **$2.19 per 30-day month**. The current page lists a `shared-cpu-2x` Machine with 512 MB RAM at **$4.39 per month**. Prices vary by region, Machines bill by the second, outbound data is extra, and a volume or dedicated IPv4 address would add cost. [7]

Fly.io has no ongoing free tier for new organisations. Its current pricing page lists a $1 free trial with no card required. After the trial, billable use requires payment. Fly's billing documentation says monthly billing uses a credit card or credits, that most accounts require an active valid card, and that credits can be purchased with a minimum purchase of $25. [7] [8]

Fly.io is the lower-cost compute option for a small always-on container, but it requires more operational work than Render. The owner would need to manage the Machine lifecycle, deployment configuration, secrets, persistent storage, backups, and monitoring. Fly's official pages do not establish that a Nigerian signup or a specific Nigerian payment card will be accepted. That remains an owner-controlled check and no signup was attempted. [7] [8]

## Comparison with keeping the Windows VPS

The existing Windows worker and EA path remains the safest functional baseline because the final safety gates already exist in the EA and worker path. It also avoids sending a master broker password to MetaApi. Its disadvantage is the external requirement that a Windows host, terminals, bridge, worker, and EA remain online. A Windows VPS price cannot be stated honestly without selecting a provider, region, storage size, and licence tier. No Windows VPS was purchased or selected for this memo.

The phone-only MetaApi path removes the owner's need to keep a computer switched on, but it changes the safety boundary. The MT5 EA cannot run there, so the server-side connector must rebuild and test every gate before any order path is enabled. It also places broker credentials in a third-party cloud. That is a material security and operational tradeoff, not just a hosting change.

## Recommendation

Choose **Render paid 512 MB at the listed $7 per month compute price** for the first hosted-backend evaluation, plus the separate MetaApi subscription and any database or data-transfer charges. Render is recommended over Fly.io for this first phase because the existing bridge is already containerised and Render provides a simpler managed deployment surface with Docker support, TLS, health checks, logs, and background-worker support. The recommendation is conditional on confirming Nigerian signup and payment acceptance and on sizing the backend after a read-only telemetry test.

Fly.io is the lower-cost alternative if the owner prefers more operational control and accepts the additional deployment and billing work. The free tiers of both providers must not be used as the production trading backend. Render free services sleep, and Fly's free trial is temporary.

## Approval gate

Before Phase 2, the owner must reply in writing with:

1. Approval or rejection of the Render recommendation.
2. Approval to create a paid Render account and a MetaApi account, with no purchase made until the exact checkout totals are shown.
3. Confirmation of the chosen MetaApi subscription after current API and market-data charges are clarified.
4. Explicit acknowledgement that MetaApi will hold any broker password supplied to it, and approval to begin with an investor password and read-only telemetry only.
5. Confirmation that no live order may be placed during the first hosted test.

Phase 2 must not start until this approval is received. The owner should complete any signup and payment step personally. No order was placed and no profit is promised.

## References

[1]: https://metaapi.cloud/ "MetaApi current pricing and product page, checked 2026-11-06"
[2]: https://metaapi.cloud/docs/client/ "MetaApi client documentation, checked 2026-11-06"
[3]: https://metaapi.cloud/docs/provisioning/api/expertAdvisor/updateExpertAdvisor/ "MetaApi Expert Advisor API limitation, checked 2026-11-06"
[4]: https://metaapi.cloud/docs/copyfactory/faq/ "MetaApi security FAQ and investor-password guidance, checked 2026-11-06"
[5]: https://render.com/pricing "Render pricing, compute plans, and payment methods, checked 2026-11-06"
[6]: https://render.com/docs/free "Render free instance limitations, checked 2026-11-06"
[7]: https://fly.io/pricing/ "Fly.io current pricing page, checked 2026-11-06"
[8]: https://docs.fly.io/about/billing/ "Fly.io billing and payment options, checked 2026-11-06"
