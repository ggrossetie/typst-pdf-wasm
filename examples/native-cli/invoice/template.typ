// A reusable template: a function that receives data and the document body.
#let invoice(data, body) = {
  set document(title: "Facture " + data.number)
  set page(paper: "a4", margin: 2cm, footer: align(center)[#data.company])
  set text(lang: "fr")

  grid(
    columns: (1fr, auto),
    text(20pt, weight: "bold")[Facture #data.number],
    align(right)[#data.date],
  )
  v(1em)
  [*Client :* #data.customer]
  v(1em)
  table(
    columns: (1fr, auto, auto),
    align: (left, right, right),
    table.header[*Article*][*Qté*][*Prix*],
    ..data.items.map(item => (item.name, str(item.qty), [#item.price €])).flatten(),
  )
  let total = data.items.map(item => item.qty * item.price).sum()
  align(right)[*Total : #total €*]
  body
}
