use anyhow::{Context, Result, ensure};
use axum_server::accept::Accept;
use serde::{Deserialize, Serialize};
use std::{
    fs,
    future::{Ready, ready},
    io,
    net::{IpAddr, Ipv4Addr, SocketAddrV4, TcpListener},
    path::Path,
};

pub const DEFAULT_PORT: u16 = 19420;

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Settings {
    pub address: Ipv4Addr,
    pub port: u16,
}

impl Default for Settings {
    fn default() -> Self {
        Self {
            address: Ipv4Addr::UNSPECIFIED,
            port: DEFAULT_PORT,
        }
    }
}

pub fn overlay(address: Ipv4Addr) -> bool {
    let [a, b, ..] = address.octets();
    a == 100 && (64..=127).contains(&b)
}

pub fn permitted(address: Ipv4Addr) -> bool {
    address.is_private() || address.is_link_local() || overlay(address)
}

impl Settings {
    pub fn validate(self) -> Result<Self> {
        ensure!(
            self.address.is_unspecified() || permitted(self.address),
            "Use 0.0.0.0, a private IPv4 address, or an address in 100.64.0.0/10."
        );
        ensure!(self.port != 0, "Port must be between 1 and 65535.");
        Ok(self)
    }

    pub fn endpoint(self) -> SocketAddrV4 {
        SocketAddrV4::new(self.address, self.port)
    }

    pub fn overlaps(self, other: Self) -> bool {
        self.port == other.port
            && (self.address == other.address
                || self.address.is_unspecified()
                || other.address.is_unspecified())
    }

    pub fn bind(self) -> io::Result<TcpListener> {
        if !self.address.is_unspecified()
            && !if_addrs::get_if_addrs()?
                .iter()
                .any(|interface| interface.ip() == self.address)
        {
            return Err(io::Error::new(
                io::ErrorKind::AddrNotAvailable,
                "Address is not assigned to a local interface",
            ));
        }
        let socket = socket2::Socket::new(
            socket2::Domain::IPV4,
            socket2::Type::STREAM,
            Some(socket2::Protocol::TCP),
        )?;
        socket.set_reuse_address(true)?;
        socket.bind(&std::net::SocketAddr::V4(self.endpoint()).into())?;
        socket.listen(128)?;
        socket.set_nonblocking(true)?;
        Ok(socket.into())
    }

    pub fn load(path: &Path) -> Result<Self> {
        match fs::read(path) {
            Ok(bytes) => serde_json::from_slice::<Self>(&bytes)
                .context("Read listen settings")?
                .validate(),
            Err(error) if error.kind() == io::ErrorKind::NotFound => Ok(Self::default()),
            Err(error) => Err(error).context("Open listen settings"),
        }
    }

    pub fn save(self, path: &Path) -> Result<()> {
        crate::state::save(path, &serde_json::to_vec_pretty(&self)?)
    }
}

#[derive(Clone, Copy)]
pub struct PrivatePeers;

impl<S> Accept<tokio::net::TcpStream, S> for PrivatePeers {
    type Stream = tokio::net::TcpStream;
    type Service = S;
    type Future = Ready<io::Result<(Self::Stream, Self::Service)>>;

    fn accept(&self, stream: Self::Stream, service: S) -> Self::Future {
        ready(stream.peer_addr().and_then(|peer| match peer.ip() {
            IpAddr::V4(address) if permitted(address) => Ok((stream, service)),
            _ => Err(io::Error::new(
                io::ErrorKind::PermissionDenied,
                "Peer is outside the supported private networks",
            )),
        }))
    }
}
