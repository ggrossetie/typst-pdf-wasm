#import "template.typ": invoice

// Structured data from a JSON file added by the host, a string from sys.inputs.
#show: invoice.with(json("data.json"))

#text(gray)[Généré par #sys.inputs.at("generator", default: "?")]
