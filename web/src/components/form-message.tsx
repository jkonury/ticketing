export function FormMessage({ message }: { message: string }) {
  return message ? <p role="alert">{message}</p> : null;
}
