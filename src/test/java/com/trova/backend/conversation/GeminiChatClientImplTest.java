package com.trova.backend.conversation;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class GeminiChatClientImplTest {

    private static final String URL =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash-lite:generateContent";

    @Test
    void 텍스트로만_응답하면_text를_반환한다() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(URL))
                .andExpect(header("x-goog-api-key", "test-key"))
                .andRespond(withSuccess("""
                        {"candidates":[{"content":{"parts":[{"text":"안녕하세요"}],"role":"model"}}]}
                        """, MediaType.APPLICATION_JSON));

        GeminiChatClientImpl client = new GeminiChatClientImpl("test-key", builder);
        GeminiChatClient.ChatResult result = client.sendMessage(List.of(), "안녕", List.of());

        assertThat(result.text()).isEqualTo("안녕하세요");
        assertThat(result.functionCall()).isNull();
    }

    @Test
    void functionCall이_오면_thoughtSignature와_함께_반환한다() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(URL))
                .andRespond(withSuccess("""
                        {"candidates":[{"content":{"parts":[
                            {"functionCall":{"name":"find_alternatives","args":{"category":"카페","indoor":true}},"thoughtSignature":"SIG123"}
                        ],"role":"model"}}]}
                        """, MediaType.APPLICATION_JSON));

        GeminiChatClientImpl client = new GeminiChatClientImpl("test-key", builder);
        var tools = List.of(new GeminiChatClient.ToolDeclaration(
                "find_alternatives", "설명",
                Map.of("category", new GeminiChatClient.ParamSchema("string", "카테고리"))));
        GeminiChatClient.ChatResult result = client.sendMessage(List.of(), "조용한 카페 찾아줘", tools);

        assertThat(result.text()).isNull();
        assertThat(result.functionCall().name()).isEqualTo("find_alternatives");
        assertThat(result.functionCall().args()).containsEntry("category", "카페");
        assertThat(result.functionCall().thoughtSignature()).isEqualTo("SIG123");
    }

    @Test
    void functionCall_앞에_텍스트_part가_와도_functionCall을_반환한다() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(URL))
                .andRespond(withSuccess("""
                        {"candidates":[{"content":{"parts":[
                            {"text":"찾아볼게요"},
                            {"functionCall":{"name":"find_alternatives","args":{"category":"카페","indoor":true}},"thoughtSignature":"SIG123"}
                        ],"role":"model"}}]}
                        """, MediaType.APPLICATION_JSON));

        GeminiChatClientImpl client = new GeminiChatClientImpl("test-key", builder);
        var tools = List.of(new GeminiChatClient.ToolDeclaration(
                "find_alternatives", "설명",
                Map.of("category", new GeminiChatClient.ParamSchema("string", "카테고리"))));
        GeminiChatClient.ChatResult result = client.sendMessage(List.of(), "조용한 카페 찾아줘", tools);

        assertThat(result.text()).isNull();
        assertThat(result.functionCall()).isNotNull();
        assertThat(result.functionCall().name()).isEqualTo("find_alternatives");
        assertThat(result.functionCall().args()).containsEntry("category", "카페");
        assertThat(result.functionCall().thoughtSignature()).isEqualTo("SIG123");
    }

    @Test
    void sendFunctionResult은_검증된_요청_형태로_보낸다() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(URL))
                .andExpect(content().json("""
                        {
                          "contents": [
                            {"role":"user","parts":[{"text":"조용한 카페로 바꿔줘"}]},
                            {"role":"model","parts":[{"functionCall":{"name":"find_alternatives","args":{"category":"카페","indoor":true}},"thoughtSignature":"SIG123"}]},
                            {"role":"user","parts":[{"functionResponse":{"name":"find_alternatives","response":{"candidates":[]}}}]}
                          ],
                          "tools": [{"functionDeclarations":[{"name":"find_alternatives","description":"설명","parameters":{"type":"object","properties":{"category":{"type":"string","description":"카테고리"}}}}]}]
                        }
                        """))
                .andRespond(withSuccess("""
                        {"candidates":[{"content":{"parts":[{"text":"커피한약방을 추천해요"}],"role":"model"}}]}
                        """, MediaType.APPLICATION_JSON));

        GeminiChatClientImpl client = new GeminiChatClientImpl("test-key", builder);
        var tools = List.of(new GeminiChatClient.ToolDeclaration(
                "find_alternatives", "설명",
                Map.of("category", new GeminiChatClient.ParamSchema("string", "카테고리"))));
        var functionCall = new GeminiChatClient.FunctionCall(
                "find_alternatives", Map.of("category", "카페", "indoor", true), "SIG123");

        GeminiChatClient.ChatResult result = client.sendFunctionResult(
                List.of(), "조용한 카페로 바꿔줘", functionCall, Map.of("candidates", List.of()), tools);

        assertThat(result.text()).isEqualTo("커피한약방을 추천해요");
    }

    @Test
    void 호출_실패하면_functionCall_text_둘다_null이다() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(URL)).andRespond(withServerError());

        GeminiChatClientImpl client = new GeminiChatClientImpl("test-key", builder);
        GeminiChatClient.ChatResult result = client.sendMessage(List.of(), "안녕", List.of());

        assertThat(result.text()).isNull();
        assertThat(result.functionCall()).isNull();
    }
}
