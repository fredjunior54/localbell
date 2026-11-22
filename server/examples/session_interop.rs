//! Loopback-only wire fixture for the Kotlin session protocol test.

use anyhow::{Context, Result};
use localbell::session_client::{Disconnect, Event, forward, pair};
use std::{net::Ipv4Addr, time::Duration};
use tokio::net::TcpListener;

#[tokio::main]
async fn main() -> Result<()> {
    tokio::time::timeout(Duration::from_secs(15), exchange()).await?
}

async fn exchange() -> Result<()> {
    let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).await?;
    println!("{}", listener.local_addr()?.port());
    let (mut socket, _) = listener.accept().await?;
    let mut credential = None;
    let mut comparison = None;
    pair(
        &mut socket,
        [1; 32],
        |value| {
            comparison = Some(value);
        },
        || std::future::ready(true),
        |value| {
            credential = Some(value.clone());
            Ok(())
        },
    )
    .await?;
    println!("{}", comparison.context("Missing fixture comparison")?);
    drop(socket);
    let (mut socket, _) = listener.accept().await?;
    let result = forward(
        &mut socket,
        &credential.context("Missing fixture credential")?,
        true,
        |event| async move {
            matches!(event, Event::Notification(payload) if payload["key"] == "interop")
        },
    )
    .await?;
    anyhow::ensure!(result == Disconnect::Revoked, "Expected fixture revocation");
    Ok(())
}
