//! 安卓 JNI 边界：线程内句柄表避免跨线程访问 Engine，panic 不穿越 JVM。
mod config;
mod event;
mod frame;
mod private_meter;
mod session;
mod t9;

use std::cell::RefCell;
use std::collections::HashMap;
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::path::Path;
use std::sync::atomic::{AtomicI64, Ordering};

use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::{jlong, jstring};

use config::Config;
use event::Event;
use session::Session;

thread_local! {
    static SESSIONS: RefCell<HashMap<i64, Session>> = RefCell::new(HashMap::new());
}
static NEXT_ID: AtomicI64 = AtomicI64::new(1);

fn text(env: &mut JNIEnv<'_>, string: &JString<'_>) -> Result<String, String> {
    env.get_string(string)
        .map(|s| s.into())
        .map_err(|e| e.to_string())
}

fn reply(env: &mut JNIEnv<'_>, result: Result<String, String>) -> jstring {
    let value = result.unwrap_or_else(|message| serde_json::json!({"error": message}).to_string());
    env.new_string(value)
        .map_or(std::ptr::null_mut(), |s| s.into_raw())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_jianxue_ime_engine_NativeEngine_create(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    data: JString<'_>,
    user: JString<'_>,
    config: JString<'_>,
) -> jlong {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let data = text(&mut env, &data)?;
        let user = text(&mut env, &user)?;
        let config: Config =
            serde_json::from_str(&text(&mut env, &config)?).map_err(|e| e.to_string())?;
        let session = Session::open(Path::new(&data), Path::new(&user), config)?;
        let id = NEXT_ID.fetch_add(1, Ordering::Relaxed);
        SESSIONS.with(|sessions| sessions.borrow_mut().insert(id, session));
        Ok::<_, String>(id)
    }));
    match result {
        Ok(Ok(id)) => id,
        other => {
            let message = match other {
                Ok(Err(message)) => message,
                _ => "输入引擎初始化失败".into(),
            };
            let _ = env.throw_new("java/lang/IllegalStateException", message);
            0
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_jianxue_ime_engine_NativeEngine_dispatch(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
    input: JString<'_>,
) -> jstring {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let source = text(&mut env, &input)?;
        let event: Event = serde_json::from_str(&source).map_err(|e| e.to_string())?;
        SESSIONS.with(|sessions| {
            let mut sessions = sessions.borrow_mut();
            let session = sessions
                .get_mut(&handle)
                .ok_or("输入会话已失效或线程不匹配")?;
            if event.kind == "stats" {
                return Ok(session.stats().to_string());
            }
            if event.kind == "vocabulary" {
                return Ok(session.vocabulary()?.to_string());
            }
            if event.kind == "import" {
                return Ok(session.import_dictionary()?.to_string());
            }
            if event.kind == "reset" {
                return Ok(session.reset()?.to_string());
            }
            if event.kind == "configure" {
                let config = serde_json::from_str(&event.text).map_err(|e| e.to_string())?;
                session.configure(config)?;
                return serde_json::to_string(&session.frame()).map_err(|e| e.to_string());
            }
            if event.kind == "validate_phrases" {
                let phrases: Vec<qingjian_core::CustomPhrase> =
                    serde_json::from_str(&event.text).map_err(|e| e.to_string())?;
                qingjian_core::custom_phrase::validate_phrases(&phrases)?;
                return Ok("{\"valid\":true}".into());
            }
            serde_json::to_string(&session.apply(event)).map_err(|e| e.to_string())
        })
    }));
    reply(
        &mut env,
        result.unwrap_or_else(|_| Err("输入引擎异常，保留原始输入".into())),
    )
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_jianxue_ime_engine_NativeEngine_destroy(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        SESSIONS.with(|sessions| {
            if let Some(mut session) = sessions.borrow_mut().remove(&handle) {
                session.engine.flush_learning();
            }
        })
    }));
}

#[cfg(test)]
mod tests;
