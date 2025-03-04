export type UserRole =
    "garageDoor"
    | "evCharging"
    | "energyStorageSystem"
    | "energyPricing"
    | "heaterUnderFloor"
    | "environmentSensors"
    | "cameras"
    | "recordings"
    | "userSettings"
    ;

export type Level = "none" | "read" | "readWrite" | "readWriteAdmin";

export type UserSettings = {
    authorization: AuthorizationMap;
}

export type AuthorizationMap = {
    [key in UserRole]: Level;
}

export type WriteCommand = "addUser" | "removeUser" | "updateAuthorization";

export type WriteUserSettings = {
    user: string;
    command: WriteCommand;
    writeAuthorization?: WriteAuthorization
}

export type WriteAuthorization = {
    userRole: UserRole,
    level: Level,
}
