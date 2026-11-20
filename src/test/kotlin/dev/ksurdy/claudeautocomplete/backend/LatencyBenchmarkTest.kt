package dev.ksurdy.claudeautocomplete.backend

import dev.ksurdy.claudeautocomplete.completion.CompletionPostProcessor
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Measures real end-to-end completion latency through [CompletionRouter] against the installed CLI.
 * Opt-in: BENCH=1, BENCH_PROVIDER=codex|claude, BENCH_MODEL, BENCH_N, BENCH_OUT (JSONL file to append to).
 */
class LatencyBenchmarkTest {
    private val javaFile = """
        package app.orders;

        import java.math.BigDecimal;
        import java.time.Instant;
        import java.util.ArrayList;
        import java.util.List;
        import java.util.Optional;

        public class OrderService {
            private final OrderRepository orders;
            private final CustomerRepository customers;
            private final Clock clock;

            public OrderService(OrderRepository orders, CustomerRepository customers, Clock clock) {
                this.orders = orders;
                this.customers = customers;
                this.clock = clock;
            }

            public Order place(long customerId, List<OrderLine> lines) {
                Customer customer = customers.findById(customerId)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown customer " + customerId));
                Order order = new Order(customer, new ArrayList<>(lines), Instant.now(clock));
                orders.save(order);
                return order;
            }

            public Optional<Order> find(long id) {
                return orders.findById(id);
            }

    """.trimIndent()

    private val cases: List<Pair<String, CompletionContext>> = listOf(
        "single-line" to CompletionContext(
            filePath = "src/main/java/app/orders/OrderService.java", languageId = "JAVA",
            prefix = javaFile + "\n    public BigDecimal total(Order order) {\n        return order.lines().stream().map(",
            suffix = "\n    }\n}\n", relatedSnippets = emptyList(), multiline = false, maxLines = 1, indent = "4 spaces",
        ),
        "body" to CompletionContext(
            filePath = "src/main/java/app/orders/OrderService.java", languageId = "JAVA",
            prefix = javaFile + "\n    public void cancel(long id) {\n        ",
            suffix = "\n    }\n}\n", relatedSnippets = emptyList(), multiline = true, maxLines = 20, indent = "4 spaces",
            emptyBody = true,
        ),
        "blank-member" to CompletionContext(
            filePath = "src/main/java/app/orders/OrderService.java", languageId = "JAVA",
            prefix = javaFile + "\n    ",
            suffix = "\n}\n", relatedSnippets = emptyList(), multiline = true, maxLines = 20, indent = "4 spaces",
        ),
    )

    @Test
    fun `measure completion latency`() = runBlocking {
        assumeTrue(System.getenv("BENCH") == "1", "set BENCH=1 to run the latency benchmark")
        val provider = if (System.getenv("BENCH_PROVIDER") == "claude") ProviderKind.Claude else ProviderKind.Codex
        val n = System.getenv("BENCH_N")?.toInt() ?: 3
        val label = System.getenv("BENCH_LABEL") ?: "run"
        val out = System.getenv("BENCH_OUT")?.let(::File)
        val config = BackendConfig(
            provider,
            testConfig(claudePath = "", model = System.getenv("BENCH_MODEL") ?: "haiku", requestTimeoutMs = 30000),
            testCodexConfig(codexPath = "", model = System.getenv("BENCH_MODEL") ?: "gpt-6-luna", requestTimeoutMs = 30000,
                persistentProcess = System.getenv("BENCH_PERSISTENT") != "0"),
        )
        val router = CompletionRouter()
        try {
            val warmStart = System.nanoTime()
            router.warmUp(config)
            println("BENCH warmup_ms=${(System.nanoTime() - warmStart) / 1_000_000}")
            if (System.getenv("BENCH_CANCEL") == "1") {
                val (_, ctx) = cases[1]
                val job = kotlinx.coroutines.GlobalScope.async { router.complete(ctx, config) }
                kotlinx.coroutines.delay(600)
                job.cancel()
                val start = System.nanoTime()
                val after = router.complete(cases[0].second, config)
                val ms = (System.nanoTime() - start) / 1_000_000
                val text = (after as? CompletionResult.Success)?.let { CompletionPostProcessor.process(it.text, cases[0].second) }
                println("BENCH cancel-then-complete ok=${after is CompletionResult.Success} ms=$ms text=$text")
                out?.appendText("""{"label":"$label-cancel","provider":"$provider","ok":${after is CompletionResult.Success},"total_ms":$ms,"text":${com.google.gson.JsonPrimitive(text ?: after.toString())}}""" + "\n")
            }
            repeat(n) { round ->
                for ((name, context) in cases) {
                    val start = System.nanoTime()
                    var firstMs = -1L
                    val result = router.complete(context, config) { if (firstMs < 0) firstMs = (System.nanoTime() - start) / 1_000_000 }
                    val totalMs = (System.nanoTime() - start) / 1_000_000
                    val text = (result as? CompletionResult.Success)?.let { CompletionPostProcessor.process(it.text, context) }
                    val ok = result is CompletionResult.Success
                    val line = """{"label":"$label","provider":"$provider","model":"${config.claude.model.takeIf { provider == ProviderKind.Claude } ?: config.codex.model}","round":$round,"case":"$name","ok":$ok,"first_ms":$firstMs,"total_ms":$totalMs,"text":${com.google.gson.JsonPrimitive((text ?: result.toString()).take(160))}}"""
                    println("BENCH $line")
                    out?.appendText(line + "\n")
                }
            }
        } finally {
            router.shutdown()
        }
    }
}
