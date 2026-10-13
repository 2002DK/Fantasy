import { createContext, useContext } from 'react'

export const PlayerContext = createContext(() => {})

/**
 * Opens the player detail dialog for a player ID; available anywhere under PlayerDialogProvider.
 * Null where player details are unavailable.
 */
export function useOpenPlayer() {
  return useContext(PlayerContext)
}
