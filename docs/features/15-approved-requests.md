# 15. Approved requests (removed)

The office does not see single approved requests: there is no `/office/requests`, no CSV export of requests and no
Requests tab on the ship page. The Master collects approved requests into one ship order and sends that
([23](23-ship-order.md)), and the office works on the orders ([24](24-office-orders.md)). The number is kept so the
other feature files keep their numbers.

Hibernate does not drop columns or tables, so a database from before P36 keeps `item_request.exported_at`,
`item_request.exported_by_id`, `request_export` and `request_export_request`, unused; drop them by hand if you like.
