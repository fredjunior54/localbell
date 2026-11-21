//! Certificate-bound comparison for reverse-session enrollment.

use sha2::{Digest, Sha256};

type Digest32 = [u8; 32];

pub fn commitment(leaf: &Digest32, code: &Digest32, challenge: &Digest32) -> Digest32 {
    transcript(b"localbell-session-commit-v1\0", &[leaf, code, challenge])
}

pub fn comparison(
    leaf: &Digest32,
    code: &Digest32,
    nonce: &Digest32,
    challenge: &Digest32,
) -> String {
    let digest = transcript(
        b"localbell-session-compare-v1\0",
        &[leaf, code, nonce, challenge],
    );
    let value = u32::from_be_bytes([digest[0], digest[1], digest[2], digest[3]]) % 1_000_000;
    format!("{value:06}")
}

fn transcript(domain: &[u8], fields: &[&Digest32]) -> Digest32 {
    let mut digest = Sha256::new_with_prefix(domain);
    for field in fields {
        digest.update(field);
    }
    digest.finalize().into()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn matches_android_pairing_vector() {
        let leaf = [0; 32];
        let code = [1; 32];
        let nonce = [2; 32];
        let challenge = [3; 32];
        assert_eq!(
            hex::encode(commitment(&leaf, &code, &challenge)),
            "4490a209f578c7cf67c098543f7b0c9d5b0920e715b9002b97ccf1e7d04c59c8"
        );
        assert_eq!(comparison(&leaf, &code, &nonce, &challenge), "232961");
    }
}
