"use client";

import { API_URL } from "@/lib/api";

type CredentialDescriptorJson = Omit<PublicKeyCredentialDescriptor, "id"> & { id: string };
type RegistrationOptionsJson = Omit<
  PublicKeyCredentialCreationOptions,
  "challenge" | "user" | "excludeCredentials"
> & {
  challenge: string;
  user: Omit<PublicKeyCredentialUserEntity, "id"> & { id: string };
  excludeCredentials?: CredentialDescriptorJson[];
};
type AuthenticationOptionsJson = Omit<
  PublicKeyCredentialRequestOptions,
  "challenge" | "allowCredentials"
> & {
  challenge: string;
  allowCredentials?: CredentialDescriptorJson[];
};

function decodeBase64Url(value: string): ArrayBuffer {
  const base64 = value.replace(/-/g, "+").replace(/_/g, "/");
  const padded = base64.padEnd(Math.ceil(base64.length / 4) * 4, "=");
  const binary = window.atob(padded);
  return Uint8Array.from(binary, (character) => character.charCodeAt(0)).buffer;
}

function encodeBase64Url(value: ArrayBuffer): string {
  const bytes = new Uint8Array(value);
  let binary = "";
  bytes.forEach((byte) => {
    binary += String.fromCharCode(byte);
  });
  return window.btoa(binary).replace(/=/g, "").replace(/\+/g, "-").replace(/\//g, "_");
}

async function post(path: string, body?: unknown): Promise<Response> {
  return fetch(`${API_URL}${path}`, {
    method: "POST",
    credentials: "include",
    headers: { "Content-Type": "application/json" },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
}

async function requireJson<T>(response: Response, fallback: string): Promise<T> {
  const payload = (await response.json().catch(() => null)) as
    | ({ detail?: string; message?: string } & T)
    | null;
  if (!response.ok) {
    throw new Error(payload?.detail || payload?.message || fallback);
  }
  return payload as T;
}

export function passkeysSupported(): boolean {
  return typeof window !== "undefined" && "PublicKeyCredential" in window;
}

export type PasskeyAttachment = "platform" | "cross-platform";

export async function registerPasskey(
  label: string,
  attachment: PasskeyAttachment = "platform",
): Promise<void> {
  if (!passkeysSupported()) throw new Error("Этот браузер не поддерживает passkey");
  const optionsJson = await requireJson<RegistrationOptionsJson>(
    await post("/webauthn/register/options"),
    "Не удалось получить параметры passkey",
  );
  const publicKey: PublicKeyCredentialCreationOptions = {
    ...optionsJson,
    challenge: decodeBase64Url(optionsJson.challenge),
    user: { ...optionsJson.user, id: decodeBase64Url(optionsJson.user.id) },
    excludeCredentials: optionsJson.excludeCredentials?.map((credential) => ({
      ...credential,
      id: decodeBase64Url(credential.id),
    })),
    authenticatorSelection: {
      ...optionsJson.authenticatorSelection,
      authenticatorAttachment: attachment,
      residentKey: "required",
      requireResidentKey: true,
      userVerification: "required",
    },
  };
  const credential = (await navigator.credentials.create({ publicKey })) as PublicKeyCredential | null;
  if (!credential) throw new Error("Создание passkey отменено");
  const attestation = credential.response as AuthenticatorAttestationResponse;
  await requireJson<{ success: boolean }>(
    await post("/webauthn/register", {
      publicKey: {
        credential: {
          id: credential.id,
          rawId: encodeBase64Url(credential.rawId),
          response: {
            attestationObject: encodeBase64Url(attestation.attestationObject),
            clientDataJSON: encodeBase64Url(attestation.clientDataJSON),
            transports: attestation.getTransports?.() || [],
          },
          type: credential.type,
          clientExtensionResults: credential.getClientExtensionResults(),
          authenticatorAttachment: credential.authenticatorAttachment,
        },
        label,
      },
    }),
    "Не удалось сохранить passkey",
  );
}

export async function authenticateWithPasskey(): Promise<void> {
  if (!passkeysSupported()) throw new Error("Этот браузер не поддерживает passkey");
  const optionsJson = await requireJson<AuthenticationOptionsJson>(
    await post("/webauthn/authenticate/options"),
    "Не удалось получить параметры входа",
  );
  const publicKey: PublicKeyCredentialRequestOptions = {
    ...optionsJson,
    challenge: decodeBase64Url(optionsJson.challenge),
    allowCredentials: optionsJson.allowCredentials?.map((credential) => ({
      ...credential,
      id: decodeBase64Url(credential.id),
    })),
  };
  const credential = (await navigator.credentials.get({ publicKey })) as PublicKeyCredential | null;
  if (!credential) throw new Error("Вход по passkey отменён");
  const assertion = credential.response as AuthenticatorAssertionResponse;
  await requireJson<{ authenticated: boolean }>(
    await post("/login/webauthn", {
      id: credential.id,
      rawId: encodeBase64Url(credential.rawId),
      response: {
        authenticatorData: encodeBase64Url(assertion.authenticatorData),
        clientDataJSON: encodeBase64Url(assertion.clientDataJSON),
        signature: encodeBase64Url(assertion.signature),
        userHandle: assertion.userHandle ? encodeBase64Url(assertion.userHandle) : null,
      },
      type: credential.type,
      clientExtensionResults: credential.getClientExtensionResults(),
      authenticatorAttachment: credential.authenticatorAttachment,
    }),
    "Passkey не принят",
  );
}
