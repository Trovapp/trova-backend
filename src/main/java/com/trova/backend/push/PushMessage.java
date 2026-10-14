package com.trova.backend.push;

/** 알림 하나의 내용. open은 앱이 열 화면(draft:{id} 등), tag는 기기에서 같은 알림을 하나로 바꿔 보일 묶음. */
public record PushMessage(String title, String body, String open, String tag) { }
