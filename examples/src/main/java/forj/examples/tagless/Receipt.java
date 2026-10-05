package forj.examples.tagless;

public record Receipt(Order order, String transaction) {
    public String summary() {
        return s"${order.quantity()} x ${order.item()} for ${order.customer()} (${transaction})";
    }
}
