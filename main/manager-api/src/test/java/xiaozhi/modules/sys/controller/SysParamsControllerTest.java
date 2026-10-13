package xiaozhi.modules.sys.controller;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.never;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.web.client.RestTemplate;

import xiaozhi.common.constant.Constant;
import xiaozhi.common.exception.ErrorCode;
import xiaozhi.common.exception.RenException;
import xiaozhi.common.utils.MessageUtils;
import xiaozhi.modules.config.service.ConfigService;
import xiaozhi.modules.sys.dto.SysParamsDTO;
import xiaozhi.modules.sys.service.SysParamsService;
import xiaozhi.modules.sys.utils.WebSocketValidator;

class SysParamsControllerTest {
    private final SysParamsService params = mock(SysParamsService.class);
    private final ConfigService config = mock(ConfigService.class);
    private final RestTemplate http = mock(RestTemplate.class);
    private final SysParamsController controller = new SysParamsController(params, config, http);

    private SysParamsDTO parameter(String code, String value) {
        SysParamsDTO dto = new SysParamsDTO();
        dto.setId(1L);
        dto.setParamCode(code);
        dto.setParamValue(value);
        dto.setValueType("string");
        return dto;
    }

    @Test
    void savesOtaWithoutRequestingItsPublicAddress() {
        SysParamsDTO dto = parameter(Constant.SERVER_OTA, "http://192.0.2.1:8002/xiaozhi/ota/");
        assertDoesNotThrow(() -> controller.update(dto));
        verify(params).update(dto);
        verify(config).getConfig(false);
        verifyNoInteractions(http);
    }

    @Test
    void savesWebSocketEvenWhenConnectionTestWouldFail() {
        String url = "ws://192.0.2.1:8000/xiaozhi/v1/";
        SysParamsDTO dto = parameter(Constant.SERVER_WEBSOCKET, url);
        try (MockedStatic<WebSocketValidator> validator = mockStatic(WebSocketValidator.class)) {
            validator.when(() -> WebSocketValidator.validateUrlFormat(url)).thenCallRealMethod();
            validator.when(() -> WebSocketValidator.testConnection(url)).thenReturn(false);
            validator.clearInvocations();
            assertDoesNotThrow(() -> controller.update(dto));
            validator.verify(() -> WebSocketValidator.testConnection(url), never());
        }
        verify(params).update(dto);
        verify(config).getConfig(false);
    }

    @Test
    void stillRejectsInvalidOrLocalAddresses() {
        try (MockedStatic<MessageUtils> messages = mockStatic(MessageUtils.class)) {
            assertRejected(Constant.SERVER_WEBSOCKET, "http://192.0.2.1:8000/xiaozhi/v1/", ErrorCode.WEBSOCKET_URL_FORMAT_ERROR);
            assertRejected(Constant.SERVER_WEBSOCKET, "ws://127.0.0.1:8000/xiaozhi/v1/", ErrorCode.WEBSOCKET_URL_LOCALHOST);
            assertRejected(Constant.SERVER_OTA, "ftp://192.0.2.1/xiaozhi/ota/", ErrorCode.OTA_URL_PROTOCOL_ERROR);
            assertRejected(Constant.SERVER_OTA, "http://192.0.2.1/xiaozhi/", ErrorCode.OTA_URL_FORMAT_ERROR);
            assertRejected(Constant.SERVER_OTA, "http://127.0.0.1:8002/xiaozhi/ota/", ErrorCode.OTA_URL_LOCALHOST);
        }
        verifyNoInteractions(params, config, http);
    }

    private void assertRejected(String code, String value, int errorCode) {
        RenException error = assertThrows(RenException.class, () -> controller.update(parameter(code, value)));
        assertEquals(errorCode, error.getCode());
    }
}
