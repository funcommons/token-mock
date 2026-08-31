package fun.commons.tokenmock.handler;

/**
 * Each protocol (openai/anthropic/gemini/...) implements this interface
 * to dispatch incoming requests for any vendor using that protocol.
 */
public interface ProtocolHandler {

    /** Protocol name (matches VendorConfig.protocol). */
    String protocol();

    /** Dispatch a single request. Throws on auth/path/validation failures. */
    Object handle(MockRequest request);
}
